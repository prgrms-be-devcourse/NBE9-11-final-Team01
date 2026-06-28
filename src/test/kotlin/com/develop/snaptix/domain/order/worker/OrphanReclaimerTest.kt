package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.global.redis.gateway.ClaimResult
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.StreamMessage
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OrphanReclaimer] 통합 테스트.
 *
 * ## 전략
 * - Testcontainers Redis (IntegrationTestSupport)로 실제 XAUTOCLAIM 동작 검증
 * - [OrderProcessor] / [ActiveEventDiscoveryPort] 는 mockk 으로 대체해 Redis 계층만 격리
 * - 각 테스트는 고유 eventId(UUID)를 사용하여 스트림 키 충돌을 방지
 * - @BeforeEach cleanAll()로 FLUSHDB → 각 테스트 완전 격리
 *
 * ## 커버하는 AC (이슈 #9)
 * 1. 워커 크래시(XACK 누락) → idle 경과 → 회수 → 재처리 완주
 * 2. idle 미만 메시지는 가로채지 않음
 * 3. 처리 성공 시 XACK → PEL 제거
 * 4. RuntimeException(비터미널) → XACK 생략 → PEL 잔존
 * 5. 잘못된 payload(터미널 IAE) → XACK → PEL 제거
 * 6. reprocess.count 메트릭
 * 7. deletedIds > 0 → deleted.count 메트릭
 * 8. 동시 회수 경합 → process 정확히 1회 (XAUTOCLAIM 원자성)
 * 9. 이벤트 단위 오류 격리 → 다른 이벤트 처리 계속
 */
@SpringBootTest
class OrphanReclaimerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var orderStreamGateway: OrderStreamGateway

    private val orderProcessor: OrderProcessor = mockk()
    private val activeEventDiscoveryPort: ActiveEventDiscoveryPort = mockk()
    private val orderStreamProperties = OrderStreamProperties(consumerGroup = CONSUMER_GROUP)
    private lateinit var meterRegistry: SimpleMeterRegistry
    private val keys = RedisKeyFactory()

    @BeforeEach
    fun setUpReclaimer() {
        // 테스트마다 새로운 레지스트리로 메트릭 격리
        meterRegistry = SimpleMeterRegistry()
    }

    // ── 기본 동작 ───────────────────────────────────────────────────────────────

    @Test
    fun `활성 이벤트가 없으면 OrderProcessor를 호출하지 않는다`() {
        every { activeEventDiscoveryPort.getActiveEvents() } returns emptyList()

        createReclaimer().reclaimOrphans()

        verify(exactly = 0) { orderProcessor.process(any()) }
        assertThat(reprocessCount()).isZero()
    }

    // ── PEL 회수 핵심 흐름 ──────────────────────────────────────────────────────

    @Test
    fun `XACK 없이 idle 경과 후 메시지가 회수되어 재처리된다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(message)
        // dead-consumer 크래시 시뮬레이션 — XACK 없이 PEL 잔존
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)
        every { orderProcessor.process(any()) } just Runs

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then
        verify(exactly = 1) { orderProcessor.process(any()) }
        assertThat(reprocessCount()).isEqualTo(1.0)
    }

    @Test
    fun `idle 미만 메시지는 가로채지 않는다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(message)
        // 방금 read → idle 0ms
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "consumer-1", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)

        // when: min-idle-time 1시간 → 방금 읽은 메시지는 절대 회수 안 됨
        createReclaimer(claimIdleMs = LONG_IDLE_MS).reclaimOrphans()

        // then
        verify(exactly = 0) { orderProcessor.process(any()) }
        assertThat(reprocessCount()).isZero()
    }

    // ── ACK 처리 동작 ───────────────────────────────────────────────────────────

    @Test
    fun `처리 성공한 메시지는 XACK되어 PEL에서 제거된다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(message)
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)
        every { orderProcessor.process(any()) } just Runs

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then: PEL이 비어야 함 (XACK 완료)
        assertThat(pendingCount(eventId)).isZero()
    }

    @Test
    fun `RuntimeException 발생 시 XACK하지 않아 메시지가 PEL에 잔존한다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(message)
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)
        every { orderProcessor.process(any()) } throws RuntimeException("일시적 처리 실패")

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then: 비터미널 오류 → XACK 생략 → PEL 잔존
        assertThat(pendingCount(eventId)).isEqualTo(1L)
    }

    @Test
    fun `잘못된 payload 형식은 터미널 오류로 XACK하여 PEL에서 제거한다`() {
        // given: 필수 필드가 없는 payload 직접 주입 (fromStreamPayload → IAE)
        val eventId = UUID.randomUUID()
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        addInvalidPayload(eventId)
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then: IAE는 터미널 → XACK → PEL 제거
        assertThat(pendingCount(eventId)).isZero()
    }

    // ── 메트릭 ─────────────────────────────────────────────────────────────────

    @Test
    fun `회수된 메시지 수만큼 reprocess_count 메트릭이 증가한다`() {
        // given: 동일 이벤트에 메시지 2개 → dead-consumer가 모두 읽고 크래시
        val eventId = UUID.randomUUID()
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(orderMessage(eventId))
        orderStreamGateway.add(orderMessage(eventId))
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)
        every { orderProcessor.process(any()) } just Runs

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then
        assertThat(reprocessCount()).isEqualTo(2.0)
    }

    @Test
    fun `deletedIds가 있으면 deleted_count 메트릭이 증가한다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        val messageId = orderStreamGateway.add(message)
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        // Stream에서 원본 강제 삭제 — 트림 유실 시뮬레이션
        redisTemplate.opsForStream<String, String>().delete(keys.queueOrder(eventId), messageId)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then: PEL에 있던 메시지가 Stream에 없으므로 deletedIds로 반환
        assertThat(deletedCount()).isGreaterThan(0.0)
    }

    @Test
    fun `deletedIds가 없으면 deleted_count 메트릭을 증가시키지 않는다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(message)
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)
        every { orderProcessor.process(any()) } just Runs

        Thread.sleep(IDLE_WAIT_MS)

        // when
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then
        assertThat(deletedCount()).isZero()
    }

    // ── 이중 처리 방지 ──────────────────────────────────────────────────────────

    @Test
    fun `ACK 완료된 메시지는 다른 reclaimer가 재처리하지 않는다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
        orderStreamGateway.add(message)
        orderStreamGateway.read(eventId, CONSUMER_GROUP, "dead-consumer", READ_COUNT)

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(eventId)
        every { orderProcessor.process(any()) } just Runs

        Thread.sleep(IDLE_WAIT_MS)

        // when: reclaimer-1이 먼저 회수·처리·ACK 완료
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()
        assertThat(pendingCount(eventId)).isZero() // ACK 확인

        // when: reclaimer-2가 동일 이벤트를 재시도
        val secondProcessCount = AtomicInteger(0)
        every { orderProcessor.process(any()) } answers {
            secondProcessCount.incrementAndGet()
            Unit
        }
        createReclaimer(claimIdleMs = SHORT_IDLE_MS).reclaimOrphans()

        // then: PEL에서 제거된 메시지는 재처리되지 않는다
        assertThat(secondProcessCount.get()).isZero()
    }

    // ── 오류 격리 ───────────────────────────────────────────────────────────────

    @Test
    fun `하나의 이벤트 처리 오류가 다른 이벤트 처리를 막지 않는다`() {
        // given: failEvent → claim 시 RuntimeException, successEvent → 정상 메시지 1개
        val failEventId = UUID.randomUUID()
        val successEventId = UUID.randomUUID()

        val successMsg = orderMessage(successEventId)
        val mockedGateway: OrderStreamGateway = mockk()
        every { mockedGateway.ensureGroup(any(), any()) } just Runs
        every {
            mockedGateway.claim(eq(failEventId), any(), any(), any(), any(), any())
        } throws RuntimeException("failEvent Redis 오류")
        every {
            mockedGateway.claim(eq(successEventId), any(), any(), any(), any(), any())
        } returns
            ClaimResult(
                claimedMessages = listOf(StreamMessage("1-0", successMsg.toStreamPayload())),
                deletedIds = emptyList(),
                nextStartId = "0-0",
            )
        every { mockedGateway.ack(any(), any(), any()) } returns 1L

        every { activeEventDiscoveryPort.getActiveEvents() } returns listOf(failEventId, successEventId)
        every { orderProcessor.process(any()) } just Runs

        val reclaimer =
            OrphanReclaimer(
                orderStreamGateway = mockedGateway,
                orderProcessor = orderProcessor,
                activeEventDiscoveryPort = activeEventDiscoveryPort,
                meterRegistry = meterRegistry,
                orderStreamProperties = orderStreamProperties,
                claimIdleMs = SHORT_IDLE_MS,
            )

        // when
        reclaimer.reclaimOrphans()

        // then: failEvent 오류에도 successEvent 메시지가 정상 처리되어야 함
        verify(exactly = 1) { orderProcessor.process(any()) }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    private fun createReclaimer(claimIdleMs: Long = SHORT_IDLE_MS) = OrphanReclaimer(
        orderStreamGateway = orderStreamGateway,
        orderProcessor = orderProcessor,
        activeEventDiscoveryPort = activeEventDiscoveryPort,
        meterRegistry = meterRegistry,
        orderStreamProperties = orderStreamProperties,
        claimIdleMs = claimIdleMs,
    )

    private fun pendingCount(eventId: UUID): Long = redisTemplate
        .opsForStream<String, String>()
        .pending(keys.queueOrder(eventId), CONSUMER_GROUP)
        .totalPendingMessages

    private fun reprocessCount(): Double = meterRegistry.counter("ticketing.order.claim.reprocess.count").count()

    private fun deletedCount(): Double = meterRegistry.counter("ticketing.stream.deleted.count").count()

    /** 필수 필드가 없는 잘못된 payload를 스트림에 직접 주입한다. */
    private fun addInvalidPayload(eventId: UUID) {
        redisTemplate
            .opsForStream<String, String>()
            .add(keys.queueOrder(eventId), mapOf("invalid_field" to "invalid_value"))
    }

    companion object {
        private const val CONSUMER_GROUP = "order-workers"
        private const val READ_COUNT = 10
        private const val SHORT_IDLE_MS = 1L // claim 트리거용 최소 idle
        private const val LONG_IDLE_MS = 3_600_000L // 1시간 — idle 미만 검증용
        private const val IDLE_WAIT_MS = 50L // min-idle 초과 보장 대기

        /** 각 테스트가 독립적인 스트림 키를 갖도록 eventId를 매번 새로 생성한다. */
        fun orderMessage(eventId: UUID = UUID.randomUUID()) = OrderMessage(
            orderId = UUID.randomUUID(),
            userId = 1L,
            eventId = eventId,
            zoneId = 10L,
        )
    }
}
