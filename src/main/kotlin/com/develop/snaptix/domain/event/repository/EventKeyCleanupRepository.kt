package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.Instant

/** 스윕 후보(이벤트 publicId + 그 zone PK 목록). */
data class EventCleanupCandidate(
    val eventPublicId: String,
    val zoneIds: List<Long>,
)

/**
 * 고아 키 스윕 후보 조회. (명세서: 스윕 — DriftStockRepository 스타일)
 *
 * `events ⋈(INNER) zones` 단일 JOIN을 **단일 transaction{}** 로 읽어, `status == CLOSED` 이고
 * `updated_at >= cutoff` 인 이벤트의 `(eventPublicId, zoneIds)`를 N+1 없이 반환한다.
 * Redis 적용(cleanup)은 호출부(Service)가 트랜잭션 밖에서 수행한다.
 */
@Repository
class EventKeyCleanupRepository {
    fun findClosedCleanupTargets(cutoff: Instant): List<EventCleanupCandidate> = transaction {
        ZonesTable
            .join(
                EventsTable,
                JoinType.INNER,
                onColumn = ZonesTable.eventId,
                otherColumn = EventsTable.id,
                additionalConstraint = {
                    (EventsTable.status eq EventStatus.CLOSED.name) and
                        (EventsTable.updatedAt greaterEq cutoff)
                },
            ).select(EventsTable.publicId, ZonesTable.id)
            .map { it[EventsTable.publicId] to it[ZonesTable.id] }
            .groupBy({ it.first }, { it.second }) // Map<publicId, List<zoneId>>
            .map { (publicId, zoneIds) -> EventCleanupCandidate(publicId, zoneIds) }
    }
}
