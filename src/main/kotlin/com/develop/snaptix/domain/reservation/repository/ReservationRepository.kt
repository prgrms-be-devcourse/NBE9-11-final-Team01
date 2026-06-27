package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Exposed 기반 [ReservationQuery] 구현.
 *
 * ## #6a 추가 메서드
 * - [findInternalEventId]: zoneId(내부 Long PK) → eventId(내부 Long PK) 변환 (A안).
 *   OrderMessage.eventId는 public UUID이나 ReservationsTable.eventId는 내부 FK(Long)이므로
 *   ZonesTable을 통해 역참조한다.
 * - [existsActiveForUserAndEvent]: 1인1매 사전 검사. DB 제약(`uk_active_user_event`)이
 *   최종 방어선이며, 이 메서드는 조기 차단으로 불필요한 Lua 차감을 방지한다.
 * - [insertPending]: PENDING_PAYMENT 예약 행 신규 삽입. #6a 성공 경로 전용.
 *   제약 위반(orderId UNIQUE·uk_active_user_event) 분기는 #6b에서 처리한다.
 *
 * NOTE: `transaction {}`은 Exposed Database 연결이 구성돼 있다고 가정한다.
 */
@Repository
class ReservationRepository : ReservationQuery {
    // ── ReservationQuery 구현 ────────────────────────────────────────────

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

    // ── #6a 신규 추가 ────────────────────────────────────────────────────

    /**
     * zoneId(내부 Long PK) → eventId(내부 Long FK) 변환 (A안).
     *
     * OrderMessage.eventId(UUID)는 ReservationsTable.eventId(Long FK)와 타입이 달라
     * INSERT에 직접 사용할 수 없다. zoneId는 이미 내부 Long PK이므로 ZonesTable을 통해
     * 단순 조회로 해결한다.
     *
     * @return 내부 eventId. 해당 zoneId가 DB에 없으면 null(호출부에서 터미널 에러 처리).
     */
    fun findInternalEventId(zoneId: Long): Long? = transaction {
        ZonesTable
            .select(ZonesTable.eventId)
            .where { ZonesTable.id eq zoneId }
            .singleOrNull()
            ?.get(ZonesTable.eventId)
    }

    /**
     * 1인1매 선검사 — (userId, eventId) 조합의 유효 점유 예약 존재 여부.
     *
     * 유효 점유 = PENDING_PAYMENT · CONFIRMED. CANCELLED · RELEASED 제외.
     * DB 레벨 최종 방어선은 `idx_idempotency`(userId, eventId) 인덱스 + #6b 제약 위반 분기.
     * 이 메서드는 Lua 차감 전 조기 차단으로 불필요한 재고 감소를 방지한다.
     *
     * @param userId            내부 user PK
     * @param internalEventId   내부 event PK ([findInternalEventId]로 변환한 값)
     */
    fun existsActiveForUserAndEvent(
        userId: Long,
        internalEventId: Long,
    ): Boolean = transaction {
        ReservationsTable
            .select(ReservationsTable.id)
            .where {
                (ReservationsTable.userId eq userId) and
                    (ReservationsTable.eventId eq internalEventId) and
                    (
                        (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name) or
                            (ReservationsTable.status eq ReservationStatus.CONFIRMED.name)
                    )
            }.limit(1)
            .firstOrNull() != null
    }

    /**
     * PENDING_PAYMENT 예약 행 신규 삽입.
     *
     * 트랜잭션 경계: 이 메서드 내부의 `transaction {}`만 해당. Redis 작업(ORDER_HOLD SET 등)은
     * 반드시 이 호출 **밖**에서 수행해야 한다.
     *
     * 예외 처리 계약:
     *  - 성공: 정상 반환.
     *  - orderId UNIQUE 위반: ExposedSQLException → 호출부(#6b)가 분기 처리.
     *  - uk_active_user_event 위반: ExposedSQLException → 호출부(#6b)가 분기 처리.
     *  - 그 외 일시적 오류: 예외 전파 → 호출부([OrderProcessingService])가 보상 후 재던짐.
     *
     * @param orderId          UUID 문자열(36자). reservations.order_id UNIQUE.
     * @param userId           내부 user PK.
     * @param internalEventId  내부 event PK([findInternalEventId]로 변환).
     * @param zoneId           내부 zone PK.
     */
    fun insertPending(
        orderId: String,
        userId: Long,
        internalEventId: Long,
        zoneId: Long,
    ) {
        transaction {
            ReservationsTable.insert { row ->
                row[ReservationsTable.orderId] = orderId
                row[ReservationsTable.userId] = userId
                row[ReservationsTable.eventId] = internalEventId
                row[ReservationsTable.zoneId] = zoneId
                row[ReservationsTable.amount] = 1
                row[ReservationsTable.status] = ReservationStatus.PENDING_PAYMENT.name
            }
        }
    }

    // ── 기존 메서드 (드리프트·만료 전용) ─────────────────────────────────

    /**
     * zoneId → 유효 점유 수. **희소(sparse) Map** — 점유가 0인 zone은 키가 없다.
     * 계약: 정산 기준은 항상 이벤트의 **전체 zone 집합**(ZoneRepository.findByEventId)이며,
     *      소비자는 zone 집합을 순회하며 `map[zoneId] ?: 0`으로 조회한다.
     *      또한 본 메서드는 **호출자의 스냅샷 트랜잭션 안에서** 호출되어야 한다(일관 스냅샷, Story 13.2/13.4).
     * TODO(#146): zoneId만 projection 후 코틀린 카운트 → SQL `COUNT(*) GROUP BY zone_id`로 전환.
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
