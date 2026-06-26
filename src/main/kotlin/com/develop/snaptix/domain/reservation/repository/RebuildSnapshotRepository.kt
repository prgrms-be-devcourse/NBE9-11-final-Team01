package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.event.repository.EventDetail
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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 재구축용 SSOT 일관 스냅샷 산정. (작업 명세서 v2.1 §7 · Story 13.2)
 **활성 집합 전체 대상 3쿼리**(N 루프 없음)를 하나의 transaction{} (일관 스냅샷)에서 실행하고 인메모리로 조립한다.
 *
 *  1) 활성 이벤트 상세        : status != CLOSED  → EventDetail
 *  2) zone별 stock 집계       : zones ⋈ events(active) ⋈(LEFT) reservations(유효 점유) GROUP BY → cap·occupied
 *  3) zone별 claimed orderId  : zones ⋈ events(active) ⋈ reservations(유효 PENDING) → (zoneId, orderId)
 *
 * 유효 점유 = CONFIRMED + 윈도우 내 PENDING(created_at >= holdCutoff). (#0 계약)
 * 호출 전 Reconcile(만료 → RELEASED)이 선행되어야 유효 PENDING 이 정확하다.
 */

@Repository
class RebuildSnapshotRepository {
    fun read(holdCutoff: Instant): RebuildSnapshot = transaction {
        val events = readActiveEvents()
        if (events.isEmpty()) return@transaction RebuildSnapshot(emptyList())

        val zoneStocksByEvent = readZoneStocksByEvent(holdCutoff)
        val claimedByZone = readClaimedOrderIdsByZone(holdCutoff)

        RebuildSnapshot(
            events.map { event ->
                val zoneStocks = zoneStocksByEvent[event.id].orEmpty()
                EventRebuildData(
                    event = event,
                    totalCapacity = zoneStocks.sumOf { it.capacity },
                    zones =
                        zoneStocks.map { zs ->
                            ZoneRebuildData(
                                zoneId = zs.zoneId,
                                stock = zs.capacity - zs.occupied,
                                claimedOrderIds = claimedByZone[zs.zoneId] ?: emptyList(),
                            )
                        },
                )
            },
        )
    }

    /** (1) 활성 이벤트 상세. */
    private fun readActiveEvents(): List<EventDetail> = EventsTable
        .selectAll()
        .where { EventsTable.status neq EventStatus.CLOSED.name }
        .map {
            EventDetail(
                id = it[EventsTable.id],
                publicId = it[EventsTable.publicId],
                name = it[EventsTable.name],
                description = it[EventsTable.description],
                location = it[EventsTable.location],
                startTime = it[EventsTable.startTime],
                endTime = it[EventsTable.endTime],
                posterUrl = it[EventsTable.posterUrl],
                status = it[EventsTable.status],
            )
        }

    /** (2) zone별 capacity·occupied 집계(eventId 별 그룹). 예약 0 zone 도 LEFT JOIN 으로 포함. */
    private fun readZoneStocksByEvent(holdCutoff: Instant): Map<Long, List<ZoneStock>> {
        val occupiedCount = ReservationsTable.id.count()
        return activeZonesJoin()
            .join(
                ReservationsTable,
                JoinType.LEFT,
                onColumn = ZonesTable.id,
                otherColumn = ReservationsTable.zoneId,
                additionalConstraint = { occupiedCondition(holdCutoff) },
            ).select(ZonesTable.eventId, ZonesTable.id, ZonesTable.totalCapacity, occupiedCount)
            .groupBy(ZonesTable.eventId, ZonesTable.id, ZonesTable.totalCapacity) // SQL GROUP BY (집계 단위)
            .map {
                ZoneStock(
                    eventId = it[ZonesTable.eventId],
                    zoneId = it[ZonesTable.id],
                    capacity = it[ZonesTable.totalCapacity],
                    occupied = it[occupiedCount].toInt(),
                )
            }.groupBy { it.eventId } // Kotlin groupBy (List → Map<eventId, List<ZoneStock>>)
    }

    /** (3) zone별 유효 PENDING orderId 목록(claimed 재구축용). */
    private fun readClaimedOrderIdsByZone(holdCutoff: Instant): Map<Long, List<String>> = activeZonesJoin()
        .join(
            ReservationsTable,
            JoinType.INNER,
            onColumn = ZonesTable.id,
            otherColumn = ReservationsTable.zoneId,
            additionalConstraint = { validPendingCondition(holdCutoff) },
        ).select(ReservationsTable.zoneId, ReservationsTable.orderId)
        .map { it[ReservationsTable.zoneId] to it[ReservationsTable.orderId] } // a to b -> a=b
        .groupBy({ it.first }, { it.second }) // Kotlin groupBy → Map<zoneId, List<orderId>>

    // ── 공통 ───

    /** zones ⋈ events(status != CLOSED) — (2)·(3) 공통 활성 zone 조인. */
    private fun activeZonesJoin() = ZonesTable.join(
        EventsTable,
        JoinType.INNER,
        onColumn = ZonesTable.eventId,
        otherColumn = EventsTable.id,
        additionalConstraint = { EventsTable.status neq EventStatus.CLOSED.name },
    )

    /** 유효 점유 = CONFIRMED + 윈도우 내 PENDING. */
    private fun occupiedCondition(holdCutoff: Instant) =
        (ReservationsTable.status eq ReservationStatus.CONFIRMED.name) or validPendingCondition(holdCutoff)

    /** 윈도우 내 유효 PENDING. */
    private fun validPendingCondition(holdCutoff: Instant) =
        (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name) and
            (ReservationsTable.createdAt greaterEq holdCutoff)

    private data class ZoneStock(
        val eventId: Long,
        val zoneId: Long,
        val capacity: Int,
        val occupied: Int,
    )
}

data class RebuildSnapshot(
    val events: List<EventRebuildData>,
)

data class EventRebuildData(
    val event: EventDetail,
    val totalCapacity: Int, // 전 zone 원본 좌석 합계 → EventInfo.totalCapacity
    val zones: List<ZoneRebuildData>,
)

data class ZoneRebuildData(
    val zoneId: Long,
    val stock: Int,
    val claimedOrderIds: List<String>,
)
