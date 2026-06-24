package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.repository.EventInsertResult
import com.develop.snaptix.domain.zone.repository.ZoneInsertResult
import com.develop.snaptix.global.redis.gateway.EventLifeCycleRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration

private const val ORDER_WORKERS_GROUP = "order-workers"
private val EVENT_INFO_CACHE_TTL: Duration = Duration.ofHours(1)

@Component
class EventRedisInitializer(
    // ❌ 기존: private val redisTemplate: StringRedisTemplate 직접 의존 제거 (ArchUnit 규칙 충족)
    // ✅ 수정: 인프라 레이어 캡슐화 게이트웨이 주입
    private val eventLifeCycleRedisGateway: EventLifeCycleRedisGateway,
    private val objectMapper: ObjectMapper,
) {
    fun initialize(
        event: EventInsertResult,
        request: EventBulkCreateRequest,
        zones: List<ZoneInsertResult>,
    ) {
        val keys = buildKeys(event, zones)
        val arguments = buildArguments(event, request, zones)

        // ✅ 수정: 저수준 스크립트 직접 실행을 게이트웨이 안전 위임 채널로 교체
        eventLifeCycleRedisGateway.initializeEventInfrastructure(keys, arguments)
    }

    private fun stockKey(zoneId: Long): String = "ZONE:$zoneId:stock"

    private fun eventInfoKey(eventId: String): String = "event:info:$eventId"

    private fun orderStreamKey(eventId: String): String = "queue:order:$eventId"

    private fun buildKeys(
        event: EventInsertResult,
        zones: List<ZoneInsertResult>,
    ): List<String> = buildList {
        add(eventInfoKey(event.publicId))
        add(orderStreamKey(event.publicId))
        zones.forEach { add(stockKey(it.id)) }
    }

    private fun buildArguments(
        event: EventInsertResult,
        request: EventBulkCreateRequest,
        zones: List<ZoneInsertResult>,
    ): List<String> {
        val cacheJson = objectMapper.writeValueAsString(event.toEventInfo(request))

        return buildList {
            add(EVENT_INFO_CACHE_TTL.seconds.toString())
            add(ORDER_WORKERS_GROUP)
            add(cacheJson)
            add(zones.size.toString())
            zones.forEach { add(it.totalCapacity.toString()) }
        }
    }

    private fun EventInsertResult.toEventInfo(request: EventBulkCreateRequest): EventInfo = EventInfo(
        eventId = publicId,
        name = request.name,
        description = request.description.orEmpty(),
        location = request.location,
        startTime = request.startTime.toInstant().toString(),
        endTime = request.endTime.toInstant().toString(),
        status = "PENDING",
        posterUrl = request.posterUrl.orEmpty(),
    )
}
