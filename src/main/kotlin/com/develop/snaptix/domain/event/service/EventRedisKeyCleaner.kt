package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.config.EventCleanupProperties
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.global.redis.gateway.EventLifeCycleRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.random.Random

@Component
class EventRedisKeyCleaner(
    private val eventLifeCycleRedisGateway: EventLifeCycleRedisGateway,
    private val eventCleanupProperties: EventCleanupProperties,
    private val orderStreamProperties: OrderStreamProperties,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * CLOSED 이벤트 키 정리. 🔄 **지터 TTL 먼저 → DEL** 순서.
     *  - claimed/stock 에 지터 TTL 부여(백스톱) → DEL 실패/앱 사망에도 자가 만료(snowstorm 분산).
     *  - 이후 즉시 DEL + Stream(가드) 정리. 멱등.
     * @return 삭제된 키 총 개수(스윕에서 cleaned/skipped 분류용).
     */
    fun cleanup(target: EventRedisCleanupTarget): Long {
        val immediateKeys = target.toImmediateCleanupKeys()
        if (immediateKeys.isEmpty()) {
            return 0L
        }

        // (1) 백스톱: claimed/stock 에 지터 TTL 먼저 부여(event:info 는 이미 TTL이라 제외)
        val expirableKeys = target.zoneIds.flatMap { listOf(stockKey(it), claimedKey(it)) }
        eventLifeCycleRedisGateway.expireKeys(expirableKeys, jitteredCleanupTtl())

        // (2) 즉시 정리
        val deletedCount = eventLifeCycleRedisGateway.deleteImmediateKeys(immediateKeys)
        val streamDeletedCount = cleanupOrderStream(target)

        logger.info {
            "[EVENT_REDIS_CLEANUP] eventPublicId=${target.eventPublicId}, zoneCount=${target.zoneIds.size}, " +
                "requestedKeys=${immediateKeys.size + 1}, deletedKeys=${deletedCount + streamDeletedCount}"
        }
        return deletedCount + streamDeletedCount
    }

    /** cleanupTtl × (1 ± jitter). 이벤트 단위 난수 오프셋으로 동시 만료 분산. */
    private fun jitteredCleanupTtl(): Duration {
        val jitter = eventCleanupProperties.ttlJitter
        if (jitter <= 0.0) {
            return eventCleanupProperties.ttl
        }
        val factor = 1.0 + Random.nextDouble(-jitter, jitter)
        return Duration.ofMillis((eventCleanupProperties.ttl.toMillis() * factor).toLong())
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
        return eventLifeCycleRedisGateway.deleteImmediateKeys(listOf(streamKey))
    }

    private fun getOrderStreamStatus(streamKey: String): OrderStreamStatus {
        val streamLength = eventLifeCycleRedisGateway.getStreamLength(streamKey)
        if (streamLength == 0L) {
            return OrderStreamStatus(0L, 0L, true)
        }

        val groupInfo = eventLifeCycleRedisGateway.getStreamGroupInfo(streamKey, orderStreamProperties.consumerGroup)
        val pendingCount = groupInfo?.pendingCount ?: 0L

        val groupLastDeliveredId = groupInfo?.lastDeliveredId
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
