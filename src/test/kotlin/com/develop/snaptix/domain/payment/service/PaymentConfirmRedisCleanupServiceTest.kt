package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.event.repository.EventRecord
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.subscribe.SseEventPublisher
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [PaymentConfirmRedisCleanupService] 단위 테스트.
 *
 * ## 전략
 * - 모든 의존성은 MockK 로 대체 — Spring 컨텍스트 없이 순수 로직만 검증한다.
 * - [StockReleaseService]와 달리 **모든 단계가 soft-fail**이므로 예외 전파 케이스가 없다.
 * - [IdempotencyRedisGateway.markCompleted]는 eventPublicId가 있을 때만 호출된다.
 *
 * ## 커버하는 AC
 * 1. 정상 흐름: 5단계 모두 올바른 인자로 호출
 * 2. eventPublicId 조회 실패 → markCompleted 생략, 나머지 단계는 실행
 * 3. 각 단계 실패 → 예외 미전파, 이후 단계 계속 실행 (soft-fail 연쇄 검증)
 * 4. SSE: TICKET_ISSUED·terminal=true·올바른 채널 키
 * 5. 단계 순서: findById → removeClaimed → markCompleted → delete → publish
 */
@DisplayName("PaymentConfirmRedisCleanupService 단위 테스트")
class PaymentConfirmRedisCleanupServiceTest {
    // ── 의존성 mock ─────────────────────────────────────────────────────────────
    private val eventRepository = mockk<EventRepository>()

    // removeClaimed/delete/publish는 Unit 반환 — relaxed mock으로 명시 스텁 불필요
    private val stockRedisGateway = mockk<StockRedisGateway>(relaxed = true)
    private val orderHoldRedisGateway = mockk<OrderHoldRedisGateway>(relaxed = true)
    private val sseEventPublisher = mockk<SseEventPublisher>(relaxed = true)

    // markCompleted는 Boolean 반환 — BeforeEach에서 명시 스텁
    private val idempotencyRedisGateway = mockk<IdempotencyRedisGateway>(relaxed = true)

    private lateinit var sut: PaymentConfirmRedisCleanupService

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────
    private val orderId = UUID.randomUUID()
    private val zoneId = 10L
    private val userId = 1L
    private val internalEventId = 42L
    private val eventPublicId = UUID.randomUUID()

    private val eventRecord =
        EventRecord(
            id = internalEventId,
            publicId = eventPublicId.toString(),
            name = "Test Event",
            status = "ON_SALE",
        )

    /** SSE 채널 키 — data class 동등 비교로 verify에서 바로 사용한다. */
    private val orderSseKey = SseChannelKey(resource = "order", id = orderId.toString())

    @BeforeEach
    fun setUp() {
        sut =
            PaymentConfirmRedisCleanupService(
                eventRepository = eventRepository,
                stockRedisGateway = stockRedisGateway,
                idempotencyRedisGateway = idempotencyRedisGateway,
                orderHoldRedisGateway = orderHoldRedisGateway,
                sseEventPublisher = sseEventPublisher,
            )

        // 정상 흐름 기본 스텁 — 각 중첩 클래스에서 필요한 부분만 재정의한다
        every { eventRepository.findById(internalEventId) } returns eventRecord
        every { idempotencyRedisGateway.markCompleted(any(), any()) } returns true
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 정상 흐름
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("정상 흐름")
    inner class HappyPath {
        @Test
        @DisplayName("모든 단계가 올바른 인자로 호출된다")
        fun `all steps are called with correct args`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { eventRepository.findById(internalEventId) }
            verify(exactly = 1) { stockRedisGateway.removeClaimed(zoneId, orderId) }
            verify(exactly = 1) { idempotencyRedisGateway.markCompleted(userId, eventPublicId) }
            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "TICKET_ISSUED" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("removeClaimed에 zoneId와 orderId(UUID)가 전달된다 — +1 없이 SREM만")
        fun `removeClaimed receives zoneId and orderId`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { stockRedisGateway.removeClaimed(zoneId, orderId) }
        }

        @Test
        @DisplayName("markCompleted에 userId와 eventPublicId(UUID)가 전달된다")
        fun `markCompleted receives userId and eventPublicId`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { idempotencyRedisGateway.markCompleted(userId, eventPublicId) }
        }

        @Test
        @DisplayName("findById에 internalEventId(Long)가 전달된다 — public UUID가 아님")
        fun `findById receives internalEventId not the public UUID`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { eventRepository.findById(internalEventId) }
        }

        @Test
        @DisplayName("모든 단계가 soft-fail이므로 정상 흐름에서 예외를 전파하지 않는다")
        fun `cleanup returns normally without throwing`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId) // 예외 없이 반환 자체가 검증
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1단계: eventPublicId 조회 실패
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("1단계 — eventPublicId 조회 실패 (eventRepository null 반환)")
    inner class NullEventRecord {
        @BeforeEach
        fun stubNullEventRecord() {
            every { eventRepository.findById(any()) } returns null
        }

        @Test
        @DisplayName("eventPublicId를 조회하지 못하면 markCompleted를 호출하지 않는다")
        fun `null eventPublicId skips markCompleted`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 0) { idempotencyRedisGateway.markCompleted(any(), any()) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 removeClaimed는 호출된다")
        fun `null eventPublicId still calls removeClaimed`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { stockRedisGateway.removeClaimed(zoneId, orderId) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 ORDER_HOLD DEL은 호출된다")
        fun `null eventPublicId still calls orderHold delete`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 TICKET_ISSUED SSE는 발행된다")
        fun `null eventPublicId still publishes TICKET_ISSUED SSE`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "TICKET_ISSUED" },
                )
            }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시 예외를 전파하지 않는다")
        fun `null eventPublicId does not propagate exception`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 전체 soft-fail
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("전체 soft-fail — 각 단계 실패가 이후 단계를 막지 않는다")
    inner class SoftFail {
        @Test
        @DisplayName("removeClaimed 실패 시 예외를 전파하지 않는다")
        fun `removeClaimed failure does not propagate`() {
            every { stockRedisGateway.removeClaimed(any(), any()) } throws RuntimeException("SREM 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)
        }

        @Test
        @DisplayName("removeClaimed 실패 시에도 markCompleted가 호출된다")
        fun `removeClaimed failure still calls markCompleted`() {
            every { stockRedisGateway.removeClaimed(any(), any()) } throws RuntimeException("SREM 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { idempotencyRedisGateway.markCompleted(userId, eventPublicId) }
        }

        @Test
        @DisplayName("removeClaimed 실패 시에도 ORDER_HOLD DEL이 호출된다")
        fun `removeClaimed failure still calls orderHold delete`() {
            every { stockRedisGateway.removeClaimed(any(), any()) } throws RuntimeException("SREM 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
        }

        @Test
        @DisplayName("removeClaimed 실패 시에도 SSE가 발행된다")
        fun `removeClaimed failure still publishes SSE`() {
            every { stockRedisGateway.removeClaimed(any(), any()) } throws RuntimeException("SREM 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }

        @Test
        @DisplayName("markCompleted 실패 시 예외를 전파하지 않는다")
        fun `markCompleted failure does not propagate`() {
            every { idempotencyRedisGateway.markCompleted(any(), any()) } throws
                RuntimeException("KEEPTTL 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)
        }

        @Test
        @DisplayName("markCompleted 실패 시에도 ORDER_HOLD DEL이 호출된다")
        fun `markCompleted failure still calls orderHold delete`() {
            every { idempotencyRedisGateway.markCompleted(any(), any()) } throws
                RuntimeException("KEEPTTL 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
        }

        @Test
        @DisplayName("markCompleted 실패 시에도 SSE가 발행된다")
        fun `markCompleted failure still publishes SSE`() {
            every { idempotencyRedisGateway.markCompleted(any(), any()) } throws
                RuntimeException("KEEPTTL 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }

        @Test
        @DisplayName("ORDER_HOLD DEL 실패 시 예외를 전파하지 않는다")
        fun `orderHold delete failure does not propagate`() {
            every { orderHoldRedisGateway.delete(any()) } throws RuntimeException("Redis 불가")

            sut.cleanup(orderId, zoneId, userId, internalEventId)
        }

        @Test
        @DisplayName("ORDER_HOLD DEL 실패 시에도 SSE가 발행된다")
        fun `orderHold delete failure still publishes SSE`() {
            every { orderHoldRedisGateway.delete(any()) } throws RuntimeException("Redis 불가")

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }

        @Test
        @DisplayName("SSE 발행 실패 시 예외를 전파하지 않는다")
        fun `SSE publish failure does not propagate`() {
            every { sseEventPublisher.publish(any(), any()) } throws RuntimeException("SSE 전송 실패")

            sut.cleanup(orderId, zoneId, userId, internalEventId)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // SSE 이벤트 검증
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SSE 이벤트 검증")
    inner class SseEventVerification {
        @Test
        @DisplayName("SSE 이벤트 이름은 항상 \"TICKET_ISSUED\"이다")
        fun `SSE event name is always TICKET_ISSUED`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = any(),
                    event = match { it.name == "TICKET_ISSUED" },
                )
            }
        }

        @Test
        @DisplayName("SSE 이벤트는 terminal=true이다")
        fun `SSE event is terminal`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = any(),
                    event = match { it.terminal },
                )
            }
        }

        @Test
        @DisplayName("SSE 채널 키는 order 리소스와 orderId 문자열로 구성된다")
        fun `SSE key uses order resource and orderId string`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = any(),
                )
            }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 TICKET_ISSUED SSE는 발행된다 — SSE는 eventPublicId 조건 없음")
        fun `SSE is published regardless of eventPublicId`() {
            every { eventRepository.findById(any()) } returns null

            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "TICKET_ISSUED" && it.terminal },
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 단계 순서 보장
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("단계 순서 보장")
    inner class StepOrdering {
        @Test
        @DisplayName("eventId 조회 → removeClaimed → markCompleted → DEL → SSE 순서로 실행된다")
        fun `steps execute in declared order`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verifyOrder {
                eventRepository.findById(internalEventId)
                stockRedisGateway.removeClaimed(zoneId, orderId)
                idempotencyRedisGateway.markCompleted(userId, eventPublicId)
                orderHoldRedisGateway.delete(orderId)
                sseEventPublisher.publish(any(), any())
            }
        }

        @Test
        @DisplayName("removeClaimed(2단계)는 markCompleted(3단계)보다 먼저 수행된다")
        fun `removeClaimed precedes markCompleted`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verifyOrder {
                stockRedisGateway.removeClaimed(any(), any())
                idempotencyRedisGateway.markCompleted(any(), any())
            }
        }

        @Test
        @DisplayName("ORDER_HOLD DEL(4단계)은 SSE 발행(5단계)보다 먼저 수행된다")
        fun `orderHold delete precedes SSE publish`() {
            sut.cleanup(orderId, zoneId, userId, internalEventId)

            verifyOrder {
                orderHoldRedisGateway.delete(any())
                sseEventPublisher.publish(any(), any())
            }
        }
    }
}
