package com.develop.snaptix.domain.event.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class EventRedisKeyCleaner(
    private val redisTemplate: StringRedisTemplate,
) {
    private val logger = KotlinLogging.logger {}

    fun cleanup(target: EventRedisCleanupTarget) {
        val keys = target.toRedisKeys()
        if (keys.isEmpty()) {
            return
        }

        val deletedCount = redisTemplate.delete(keys)
        logger.info {
            "[EVENT_REDIS_CLEANUP] eventPublicId=${target.eventPublicId}, zoneCount=${target.zoneIds.size}, " +
                "requestedKeys=${keys.size}, deletedKeys=$deletedCount"
        }
    }

    private fun EventRedisCleanupTarget.toRedisKeys(): List<String> =
        buildList {
            add(eventInfoKey(eventPublicId))
            add(orderStreamKey(eventPublicId))
            zoneIds.forEach { zoneId ->
                add(stockKey(zoneId))
                add(claimedKey(zoneId))
            }
        }

    private fun stockKey(zoneId: Long): String = "ZONE:$zoneId:stock"

    private fun claimedKey(zoneId: Long): String = "ZONE:$zoneId:claimed"

    private fun eventInfoKey(eventPublicId: String): String = "event:info:$eventPublicId"

    private fun orderStreamKey(eventPublicId: String): String = "queue:order:$eventPublicId"
}
