package com.develop.snaptix.domain.event.service

import com.develop.snaptix.global.redis.gateway.EventLifeCycleRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private const val ORDER_WORKERS_GROUP = "order-workers"

@Component
class EventRedisKeyCleaner(
    // ❌ 기존: private val redisTemplate: StringRedisTemplate 저수준 직접 의존 제거
    // ✅ 수정: 라이프사이클 전용 캡슐화 게이트웨이 조입
    private val eventLifeCycleRedisGateway: EventLifeCycleRedisGateway,
) {
    private val logger = KotlinLogging.logger {}

    fun cleanup(target: EventRedisCleanupTarget) {
        val keys = target.toImmediateCleanupKeys()

        // ✅ 수정: 게이트웨이 캡슐화 파이프라인으로 무효화 위임
        val deletedCount = eventLifeCycleRedisGateway.deleteImmediateKeys(keys)
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
                    "streamLength=${streamStatus.streamLength}, pendingCount=${streamStatus.pendingCount}"
            }
            return 0L
        }

        // ✅ 수정: 캡슐화 게이트웨이를 통해 Stream 최종 파괴 처리
        return eventLifeCycleRedisGateway.deleteImmediateKeys(listOf(streamKey))
    }

    private fun getOrderStreamStatus(streamKey: String): OrderStreamStatus {
        // ✅ 수정: 저수준 연산을 게이트웨이의 고수준 추상화 채널로 정합화
        val streamLength = eventLifeCycleRedisGateway.getStreamLength(streamKey)
        if (streamLength == 0L) {
            return OrderStreamStatus(0L, 0L, true)
        }

        val groupInfo = eventLifeCycleRedisGateway.getStreamGroupInfo(streamKey, ORDER_WORKERS_GROUP)
        val pendingCount = groupInfo?.pendingCount ?: 0L

        val groupLastDeliveredId = eventLifeCycleRedisGateway.getGroupLastDeliveredId(streamKey, ORDER_WORKERS_GROUP)
        val streamLastGeneratedId = eventLifeCycleRedisGateway.getStreamLastGeneratedId(streamKey)

        val hasUndeliveredMessages = streamLength > 0 && groupLastDeliveredId != streamLastGeneratedId

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
