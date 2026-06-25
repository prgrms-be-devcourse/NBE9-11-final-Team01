package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.Instant

/** 드리프트 집계 결과 (경량 projection). zoneId(Redis 키)와 expected(기대 재고)만. */
data class ZoneExpected(
    val zoneId: Long,
    val expected: Int,
)

/**
 * 드리프트 전용 재고 기대치 집계. (작업 명세서 v2.1 §6 · Story 13.4 / S-13)
 *
 * active 이벤트(`status != CLOSED`)의 zone을 `events ⋈(INNER) zones ⋈(LEFT) reservations` 로 묶어
 * **단일 SELECT(JOIN + GROUP BY)** 로 zone별 `expected = totalCapacity − (CONFIRMED + 유효 PENDING)` 산정.
 *
 *  - 단일 statement = 단일 MVCC 스냅샷 → event+zone 동일 시점 보장(격리수준 무관 원자적), N+1 제거.
 *  - LEFT JOIN 이므로 예약 0인 zone 도 `expected = totalCapacity` 로 포함(sparse Map/`getOrDefault` 불필요).
 *  - 유효 점유 = CONFIRMED + 윈도우 내 PENDING(`created_at ≥ holdCutoff`). 점유 정의는 #0 계약과 동일.
 *
 * 성능 전제: `reservations (zone_id, status, created_at)` 복합 인덱스.
 */
@Repository
class DriftStockRepository {
    fun aggregateExpectedStock(holdCutoff: Instant): List<ZoneExpected> = transaction {
        val occupied = ReservationsTable.id.count() // 길어서 따로 변수로 정의, 바로 사용 X

        ZonesTable
            .join(
                EventsTable,
                JoinType.INNER, // 매칭되는것만
                onColumn = ZonesTable.eventId,
                otherColumn = EventsTable.id,
                additionalConstraint = { EventsTable.status neq EventStatus.CLOSED.name },
            ).join(
                ReservationsTable,
                JoinType.LEFT,
                onColumn = ZonesTable.id,
                otherColumn = ReservationsTable.zoneId,
                additionalConstraint = {
                    (ReservationsTable.status eq ReservationStatus.CONFIRMED.name) or
                        (
                            (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name) and
                                (ReservationsTable.createdAt greaterEq holdCutoff)
                        )
                },
            ).select(ZonesTable.id, ZonesTable.totalCapacity, occupied)
            .groupBy(ZonesTable.id, ZonesTable.totalCapacity)
            .map {
                ZoneExpected(
                    zoneId = it[ZonesTable.id],
                    expected = it[ZonesTable.totalCapacity] - it[occupied].toInt(),
                )
            }
    }
}
