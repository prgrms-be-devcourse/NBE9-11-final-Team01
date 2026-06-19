package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.repository.EventInsertResult
import com.develop.snaptix.domain.zone.repository.ZoneInsertResult
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

private const val ORDER_WORKERS_GROUP = "order-workers"
private val EVENT_INFO_CACHE_TTL: Duration = Duration.ofHours(1)

@Component
class EventRedisInitializer(
    private val redisTemplate: StringRedisTemplate,
) {
    fun initialize(
        event: EventInsertResult,
        request: EventBulkCreateRequest,
        zones: List<ZoneInsertResult>,
    ) {
        zones.forEach { zone ->
            redisTemplate.opsForValue().set(stockKey(zone.id), zone.totalCapacity.toString())
        }

        val eventInfoKey = eventInfoKey(event.publicId)
        redisTemplate.opsForHash<String, String>().putAll(eventInfoKey, event.toCacheMap(request))
        redisTemplate.expire(eventInfoKey, EVENT_INFO_CACHE_TTL)

        createOrderConsumerGroup(event.publicId)
    }

    fun stockKey(zoneId: Long): String = "ZONE:$zoneId:stock"

    private fun eventInfoKey(eventId: String): String = "event:info:$eventId"

    private fun orderStreamKey(eventId: String): String = "queue:order:$eventId"

    private fun EventInsertResult.toCacheMap(request: EventBulkCreateRequest): Map<String, String> =
        mapOf(
            "eventId" to publicId,
            "name" to request.name,
            "description" to request.description.orEmpty(),
            "location" to request.location,
            "startTime" to request.startTime.toString(),
            "endTime" to request.endTime.toString(),
            "status" to request.initialStatus.name,
            "posterUrl" to request.posterUrl.orEmpty(),
        )

    private fun createOrderConsumerGroup(eventId: String) {
        try {
            redisTemplate.connectionFactory?.connection?.use { connection ->
                connection.execute(
                    "XGROUP",
                    "CREATE".toByteArray(),
                    orderStreamKey(eventId).toByteArray(),
                    ORDER_WORKERS_GROUP.toByteArray(),
                    "$".toByteArray(),
                    "MKSTREAM".toByteArray(),
                )
            }
        } catch (exception: RedisSystemException) {
            if (exception.message?.contains("BUSYGROUP") != true) {
                throw exception
            }
        }
    }
}
