package com.develop.snaptix.domain.event.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.develop.snaptix.domain.event.config.EventCleanupProperties
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.global.redis.gateway.EventLifeCycleRedisGateway
import com.develop.snaptix.global.redis.gateway.StreamGroupInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * EventRedisKeyCleaner 단위 테스트 (MockK).
 *  - 지터 TTL을 DEL보다 **먼저** 부여(백스톱) + 지터 범위 검증
 *  - DEL 실패해도 TTL은 이미 부여(자가 만료 보장)
 *  - Stream 미처리 메시지 있으면 stream 삭제 스킵(유실 가드)
 */
class EventRedisKeyCleanerTest {
    private val gateway = mockk<EventLifeCycleRedisGateway>(relaxed = true)
    private val properties =
        EventCleanupProperties().apply {
            ttl = Duration.ofHours(1)
            ttlJitter = 0.1
        }
    private val orderStreamProperties = OrderStreamProperties(consumerGroup = CONSUMER_GROUP)
    private val cleaner = EventRedisKeyCleaner(gateway, properties, orderStreamProperties)

    private val publicId = "evt-1"
    private val zoneId = 10L
    private val target = EventRedisCleanupTarget(eventPublicId = publicId, zoneIds = listOf(zoneId))

    private val immediateKeys = listOf("event:info:evt-1", "ZONE:10:stock", "ZONE:10:claimed")
    private val expirableKeys = listOf("ZONE:10:stock", "ZONE:10:claimed")
    private val streamKey = "queue:order:evt-1"

    @Test
    fun `claimed_stock에 지터 TTL을 DEL보다 먼저 부여한다`() {
        val ttlSlot = slot<Duration>()
        every { gateway.getStreamLength(any()) } returns 0L // stream 없음 → canDelete
        every { gateway.expireKeys(any(), capture(ttlSlot)) } returns 2L
        every { gateway.deleteImmediateKeys(any()) } returns 3L

        cleaner.cleanup(target)

        verifyOrder {
            gateway.expireKeys(expirableKeys, any())
            gateway.deleteImmediateKeys(immediateKeys)
        }
        val base = Duration.ofHours(1).toMillis()
        assertThat(ttlSlot.captured.toMillis())
            .isBetween((base * 0.9).toLong(), (base * 1.1).toLong()) // ±10% 지터 범위
    }

    @Test
    fun `DEL이 실패해도 TTL은 먼저 부여된다(백스톱)`() {
        every { gateway.getStreamLength(any()) } returns 0L
        every { gateway.deleteImmediateKeys(any()) } throws RuntimeException("redis del failed")

        assertThrows<RuntimeException> { cleaner.cleanup(target) }

        verify(exactly = 1) { gateway.expireKeys(expirableKeys, any()) } // DEL 전에 이미 호출됨
    }

    @Test
    fun `Stream에 미처리 메시지가 있으면 stream 삭제를 스킵한다`() {
        every { gateway.getStreamLength(any()) } returns 5L
        every { gateway.getStreamGroupInfo(any(), CONSUMER_GROUP) } returns
            StreamGroupInfo(lastDeliveredId = "1-0", pendingCount = 3L)
        every { gateway.getStreamLastGeneratedId(any()) } returns "1-0"

        cleaner.cleanup(target)

        verify(exactly = 1) { gateway.deleteImmediateKeys(immediateKeys) } // 즉시 키는 DEL
        verify(exactly = 0) { gateway.deleteImmediateKeys(listOf(streamKey)) } // stream은 skip
    }

    @Test
    fun `expiredCount가 EVENT_REDIS_CLEANUP 로그에 포함된다`() {
        // given
        val logger = LoggerFactory.getLogger(EventRedisKeyCleaner::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        every { gateway.getStreamLength(any()) } returns 0L
        every { gateway.expireKeys(expirableKeys, any()) } returns 2L // expiredCount = 2
        every { gateway.deleteImmediateKeys(immediateKeys) } returns 3L // eventInfo(ttl 부여 OK)까지 포함
        every { gateway.deleteImmediateKeys(listOf(streamKey)) } returns 1L

        // when
        cleaner.cleanup(target)

        // then
        val message =
            appender.list
                .find { it.formattedMessage.contains("EVENT_REDIS_CLEANUP") }
                ?.formattedMessage

        assertThat(message)
            .contains("expiredKeys=2")
            .contains("eventPublicId=$publicId")
            .contains("deletedKeys=4") // 3 + 1

        logger.detachAppender(appender)
    }

    companion object {
        private const val CONSUMER_GROUP = "order-workers"
    }
}
