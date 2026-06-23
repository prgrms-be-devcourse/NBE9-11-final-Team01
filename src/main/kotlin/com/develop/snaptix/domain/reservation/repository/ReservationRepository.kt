package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Exposed 기반 [ReservationQuery] 구현.
 * orderId(UNIQUE)로 예약 단건을 읽어 소유권·재구성에 필요한 [ReservationView]로 매핑한다.
 *
 * NOTE: `transaction {}`은 Exposed Database 연결이 구성돼 있다고 가정한다(스프링 Exposed 스타터).
 *       reservation 도메인 본개발 시 트랜잭션 경계/조회 관용구를 도메인 컨벤션에 맞춰 통합한다.
 */
@Repository
class ReservationRepository : ReservationQuery {
    override fun findByOrderId(orderId: String): ReservationView? = transaction {
        ReservationsTable
            .select(ReservationsTable.userId, ReservationsTable.status, ReservationsTable.createdAt)
            .where { ReservationsTable.orderId eq orderId }
            .limit(1)
            .map { row ->
                ReservationView(
                    userId = row[ReservationsTable.userId],
                    status = ReservationStatus.valueOf(row[ReservationsTable.status]),
                    createdAt = row[ReservationsTable.createdAt],
                )
            }.singleOrNull()
    }

    /**
     * zoneId → 유효 점유 수. **희소(sparse) Map** — 점유가 0인 zone은 키가 없다.
     * 계약: 정산 기준은 항상 이벤트의 **전체 zone 집합**(ZoneRepository.findByEventId)이며,
     *      소비자는 zone 집합을 순회하며 `map[zoneId] ?: 0`으로 조회한다.
     *      또한 본 메서드는 **호출자의 스냅샷 트랜잭션 안에서** 호출되어야 한다(일관 스냅샷, Story 13.2/13.4).
     * TODO(#146): zoneId만 projection 후 코틀린 카운트 → SQL `COUNT(*) GROUP BY zone_id`로 전환.
     * 본 메서드는 reservations만 집계하며 zone 목록을 알지 못한다(관심사 분리).
     */
    fun countOccupiedByZone(
        eventId: Long,
        holdCutoff: Instant,
    ): Map<Long, Int> = transaction {
        ReservationsTable
            .select(ReservationsTable.zoneId)
            .where {
                (ReservationsTable.eventId eq eventId) and isOccupied(holdCutoff)
            }.map { it[ReservationsTable.zoneId] }
            .groupingBy { it }
            .eachCount()
    }

    /** 만료 PENDING. id·orderId·zoneId 3컬럼만 projection. */
    fun findExpiredPending(cutoff: Instant): List<ExpiredReservation> = transaction {
        ReservationsTable
            .select(ReservationsTable.id, ReservationsTable.orderId, ReservationsTable.zoneId)
            .where {
                (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name) and
                    (ReservationsTable.createdAt less cutoff)
            }.map {
                ExpiredReservation(
                    id = it[ReservationsTable.id],
                    orderId = it[ReservationsTable.orderId],
                    zoneId = it[ReservationsTable.zoneId],
                )
            }
    }

    /** 조건부 UPDATE → RELEASED. affected rows(0/1) 반환. */
    fun releaseIfPending(id: Long): Int = transaction {
        ReservationsTable.update(
            {
                (ReservationsTable.id eq id) and
                    (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name)
            },
        ) {
            it[status] = ReservationStatus.RELEASED.name
            it[updatedAt] = Instant.now()
        }
    }

    /**
     * 유효 PENDING의 orderId를 zoneId별로. **희소 Map**(유효 PENDING 0 zone 키 부재).
     * 값(orderId 목록)이 필요해 집계 불가 → zoneId·orderId **2컬럼만** projection.
     * 호출자의 스냅샷 트랜잭션 안에서 호출되어야 한다.
     */
    fun findValidPendingOrderIds(
        eventId: Long,
        holdCutoff: Instant,
    ): Map<Long, List<String>> = transaction {
        ReservationsTable
            .select(ReservationsTable.zoneId, ReservationsTable.orderId)
            .where {
                (ReservationsTable.eventId eq eventId) and
                    (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name) and
                    (ReservationsTable.createdAt greaterEq holdCutoff)
            }.map { it[ReservationsTable.zoneId] to it[ReservationsTable.orderId] }
            .groupBy({ it.first }, { it.second })
    }

    private fun isOccupied(holdCutoff: Instant) = (ReservationsTable.status eq ReservationStatus.CONFIRMED.name) or
        (
            (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name) and
                (ReservationsTable.createdAt greaterEq holdCutoff)
        )
}
