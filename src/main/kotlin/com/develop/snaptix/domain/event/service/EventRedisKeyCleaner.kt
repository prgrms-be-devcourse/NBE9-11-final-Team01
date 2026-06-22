package com.develop.snaptix.domain.event.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

private const val ORDER_WORKERS_GROUP = "order-workers"

@Component
class EventRedisKeyCleaner(
    private val redisTemplate: StringRedisTemplate,
) {
    private val logger = KotlinLogging.logger {}

    fun cleanup(target: EventRedisCleanupTarget) {
        val keys = target.toImmediateCleanupKeys()
        if (keys.isEmpty()) {
            return
        }

        val deletedCount = redisTemplate.delete(keys)
        val streamDeletedCount = cleanupOrderStream(target)
        logger.info {
            "[EVENT_REDIS_CLEANUP] eventPublicId=${target.eventPublicId}, zoneCount=${target.zoneIds.size}, " +
                "requestedKeys=${keys.size + 1}, deletedKeys=${deletedCount + streamDeletedCount}"
        }
    }

    private fun cleanupOrderStream(target: EventRedisCleanupTarget): Long {
        val streamKey = orderStreamKey(target.eventPublicId)
        val streamStatus = getOrderStreamStatus(streamKey)

        if (!streamStatus.canDelete) {
            logger.warn {
                "[EVENT_ORDER_STREAM_DELETE_SKIPPED] eventPublicId=${target.eventPublicId}, " +
                    "streamKey=$streamKey, streamLength=${streamStatus.streamLength}, pendingCount=${streamStatus.pendingCount}"
            }
            return 0L
        }

        return if (redisTemplate.delete(streamKey)) 1L else 0L
    }

    private fun getOrderStreamStatus(streamKey: String): OrderStreamStatus {
        val streamOperations = redisTemplate.opsForStream<String, String>()
        val streamInfo =
            try {
                streamOperations.info(streamKey)
            } catch (exception: DataAccessException) {
                logger.debug(exception) { "[EVENT_ORDER_STREAM_INFO_SKIPPED] streamKey=$streamKey" }
                return OrderStreamStatus(streamLength = 0L, pendingCount = 0L, canDelete = true)
            }
        val streamLength = streamInfo.streamLength()

        val pendingCount =
            try {
                streamOperations.pending(streamKey, ORDER_WORKERS_GROUP).totalPendingMessages
            } catch (exception: DataAccessException) {
                logger.debug(exception) { "[EVENT_ORDER_STREAM_PENDING_SKIPPED] streamKey=$streamKey" }
                0L
            }
        val groupLastDeliveredId =
            try {
                streamOperations
                    .groups(streamKey)
                    .firstOrNull { it.groupName() == ORDER_WORKERS_GROUP }
                    ?.lastDeliveredId()
            } catch (exception: DataAccessException) {
                logger.debug(exception) { "[EVENT_ORDER_STREAM_GROUP_SKIPPED] streamKey=$streamKey" }
                null
            }
        val hasUndeliveredMessages = streamLength > 0 && groupLastDeliveredId != streamInfo.lastGeneratedId()

        return OrderStreamStatus(
            streamLength = streamLength,
            pendingCount = pendingCount,
            canDelete = pendingCount == 0L && !hasUndeliveredMessages,
        )
    }

    private fun EventRedisCleanupTarget.toImmediateCleanupKeys(): List<String> = buildList {
        add(eventInfoKey(eventPublicId))
        zoneIds.forEach { zoneId ->
            add(stockKey(zoneId))
            add(claimedKey(zoneId))
        }
    }

    private fun stockKey(zoneId: Long): String = "ZONE:$zoneId:stock"

    private fun claimedKey(zoneId: Long): String = "ZONE:$zoneId:claimed"

    private fun eventInfoKey(eventPublicId: String): String = "event:info:$eventPublicId"

    private fun orderStreamKey(eventPublicId: String): String = "queue:order:$eventPublicId"

    private data class OrderStreamStatus(
        val streamLength: Long,
        val pendingCount: Long,
        val canDelete: Boolean,
    )
}
