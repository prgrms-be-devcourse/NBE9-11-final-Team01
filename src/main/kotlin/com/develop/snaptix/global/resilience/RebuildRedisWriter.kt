package com.develop.snaptix.global.resilience

import com.develop.snaptix.domain.event.repository.EventDetail
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 재구축 Redis 쓰기 — **게이트웨이 위임**(executor 경유, 원자 덮어쓰기). (작업 명세서 v2.1 §7)
 *
 *  - writeEventInfo : 🔄 `EventCacheRedisGateway.put(eventPublicId, EventInfo)` (CACHE_SET, TTL 1h는 게이트웨이 책임).
 *                     `EventRebuildData → EventInfo` 매핑. CacheAsideAspect 리더와 동일 JSON 포맷 공유.
 *  - rebuildZone    : `StockRedisGateway.rebuild` 한 번으로 **stock SET + claimed 원자 덮어쓰기**(STOCK_REBUILD).
 *                     직후 +1 금지 — rebuild 가 (c)stock+(d)claimed 를 통합 처리.
 */
@Component
class RebuildRedisWriter(
    private val stockRedisGateway: StockRedisGateway,
    private val eventCacheRedisGateway: EventCacheRedisGateway,
) {
    fun writeEventInfo(
        event: EventDetail,
        totalCapacity: Int,
    ) {
        eventCacheRedisGateway.put(UUID.fromString(event.publicId), event.toEventInfo(totalCapacity))
    }

    /**
     * zone 재구축 — stock SET + claimed 원자 덮어쓰기를 게이트웨이 1회 호출로 처리.
     * `claimedOrderIds: List<String>` → `UUID` 변환 후 위임.
     */
    fun rebuildZone(
        zoneId: Long,
        stock: Int,
        claimedOrderIds: List<String>,
    ) {
        stockRedisGateway.rebuild(zoneId, stock, claimedOrderIds.map(UUID::fromString))
    }

    private fun EventDetail.toEventInfo(totalCapacity: Int): EventInfo = EventInfo(
        eventId = publicId,
        name = name,
        description = description.orEmpty(),
        location = location,
        startTime = startTime.toString(), // ISO-8601
        endTime = endTime.toString(),
        status = status, // enum name (String)
        posterUrl = posterUrl.orEmpty(),
        totalCapacity = totalCapacity,
    )
}
