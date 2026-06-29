package com.develop.snaptix.domain.order.worker.release

import com.develop.snaptix.domain.event.repository.EventRecord
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.reservation.repository.OrderIdempotencyContext
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

@DisplayName("StockReleaseService 단위 테스트")
class StockReleaseServiceTest {
    // ── 의존성 mock ─────────────────────────────────────────────────────────────
    private val reservationRepository = mockk<ReservationRepository>()
    private val eventRepository = mockk<EventRepository>()
    private val stockRedisGateway = mockk<StockRedisGateway>()

    // compareAndDelete는 Boolean 반환 — relaxed mock이 false를 반환하지만 BeforeEach에서 재정의
    private val idempotencyRedisGateway = mockk<IdempotencyRedisGateway>(relaxed = true)

    // delete/publish는 Unit 반환 — relaxed mock으로 명시 스텁 불필요
    private val orderHoldRedisGateway = mockk<OrderHoldRedisGateway>(relaxed = true)
    private val sseEventPublisher = mockk<SseEventPublisher>(relaxed = true)

    private lateinit var sut: StockReleaseService

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────
    private val orderId = UUID.randomUUID()
    private val orderIdStr = orderId.toString()
    private val zoneId = 10L
    private val userId = 1L
    private val internalEventId = 42L
    private val eventPublicId = UUID.randomUUID()

    private val context = OrderIdempotencyContext(userId = userId, internalEventId = internalEventId)
    private val eventRecord =
        EventRecord(
            id = internalEventId,
            publicId = eventPublicId.toString(),
            name = "Test Event",
            status = "ON_SALE",
        )

    /** SSE 채널 키 — data class 동등 비교로 verify에서 바로 사용한다. */
    private val orderSseKey = SseChannelKey(resource = "order", id = orderIdStr)

    @BeforeEach
    fun setUp() {
        sut =
            StockReleaseService(
                reservationRepository = reservationRepository,
                eventRepository = eventRepository,
                stockRedisGateway = stockRedisGateway,
                idempotencyRedisGateway = idempotencyRedisGateway,
                orderHoldRedisGateway = orderHoldRedisGateway,
                sseEventPublisher = sseEventPublisher,
            )

        // 정상 흐름 기본 스텁 — 각 중첩 클래스에서 필요한 부분만 재정의한다
        every { reservationRepository.findIdempotencyContextByOrderId(any()) } returns context
        every { eventRepository.findById(internalEventId) } returns eventRecord
        every { stockRedisGateway.compensate(any(), any()) } returns true
        every { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) } returns true
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
        @DisplayName("모든 단계가 올바른 인자로 호출된다 (PAYMENT_TIMEOUT)")
        fun `all steps are called with correct args on PAYMENT_TIMEOUT`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { reservationRepository.findIdempotencyContextByOrderId(orderIdStr) }
            verify(exactly = 1) { eventRepository.findById(internalEventId) }
            verify(exactly = 1) { stockRedisGateway.compensate(zoneId, orderId) }
            verify(exactly = 1) { idempotencyRedisGateway.compareAndDelete(userId, eventPublicId, orderId) }
            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "PAYMENT_TIMEOUT" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("PAYMENT_FAILED 이유는 ORDER_FAILED SSE를 발행하고 나머지 단계를 모두 실행한다")
        fun `PAYMENT_FAILED calls all steps and publishes ORDER_FAILED SSE`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_FAILED)

            verify(exactly = 1) { stockRedisGateway.compensate(zoneId, orderId) }
            verify(exactly = 1) { idempotencyRedisGateway.compareAndDelete(userId, eventPublicId, orderId) }
            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "ORDER_FAILED" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("compareAndDelete에 userId, eventPublicId(UUID), orderId(UUID)가 전달된다")
        fun `compareAndDelete receives correct userId eventPublicId and orderId`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { idempotencyRedisGateway.compareAndDelete(userId, eventPublicId, orderId) }
        }

        @Test
        @DisplayName("compensate에 zoneId와 orderId(UUID 변환값)가 전달된다")
        fun `compensate receives zoneId and orderId converted to UUID`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { stockRedisGateway.compensate(zoneId, orderId) }
        }

        @Test
        @DisplayName("findById에 internalEventId(Long)가 전달된다 — public UUID가 아님")
        fun `findById receives internalEventId not the public UUID`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { eventRepository.findById(internalEventId) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1단계: 예약 컨텍스트 없음
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("1단계 — 예약 컨텍스트 없음 (context = null)")
    inner class NullContext {
        @BeforeEach
        fun stubNullContext() {
            every { reservationRepository.findIdempotencyContextByOrderId(any()) } returns null
        }

        @Test
        @DisplayName("context가 null이면 compareAndDelete를 호출하지 않는다")
        fun `null context skips compareAndDelete`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 0) { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) }
        }

        @Test
        @DisplayName("context가 null이면 eventRepository.findById를 호출하지 않는다")
        fun `null context skips eventRepository findById`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 0) { eventRepository.findById(any()) }
        }

        @Test
        @DisplayName("context가 null이어도 compensate는 호출된다 — 재고 복구는 항상 시도")
        fun `null context still calls compensate`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { stockRedisGateway.compensate(zoneId, orderId) }
        }

        @Test
        @DisplayName("context가 null이어도 ORDER_HOLD DEL은 호출된다")
        fun `null context still calls orderHold delete`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
        }

        @Test
        @DisplayName("context가 null이어도 SSE는 발행된다")
        fun `null context still publishes SSE`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 2단계: eventPublicId 조회 실패
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("2단계 — eventPublicId 조회 실패 (eventRepository null 반환)")
    inner class NullEventRecord {
        @BeforeEach
        fun stubNullEventRecord() {
            every { eventRepository.findById(any()) } returns null
        }

        @Test
        @DisplayName("eventPublicId를 조회하지 못하면 compareAndDelete를 호출하지 않는다")
        fun `null eventPublicId skips compareAndDelete`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 0) { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 compensate는 호출된다")
        fun `null eventPublicId still calls compensate`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { stockRedisGateway.compensate(zoneId, orderId) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 ORDER_HOLD DEL은 호출된다")
        fun `null eventPublicId still calls orderHold delete`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시에도 SSE는 발행된다")
        fun `null eventPublicId still publishes SSE`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }

        @Test
        @DisplayName("eventPublicId 조회 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `null eventPublicId does not propagate and returns normally`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT) // 예외 없이 반환 자체가 검증
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 3단계: compensate 실패 (예외 전파)
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("3단계 — compensate 실패 (예외 전파, 비터미널)")
    inner class CompensateFailure {
        @BeforeEach
        fun stubCompensateFailure() {
            every { stockRedisGateway.compensate(any(), any()) } throws RuntimeException("Redis 장애")
        }

        @Test
        @DisplayName("compensate 예외는 호출자에게 전파된다 — PEL 잔존(재시도 보장)")
        fun `compensate exception propagates to caller`() {
            assertThatThrownBy { sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT) }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Redis 장애")
        }

        @Test
        @DisplayName("compensate 예외 발생 시 이후 단계(4~6)를 수행하지 않는다")
        fun `compensate failure skips all subsequent steps`() {
            runCatching { sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT) }

            verify(exactly = 0) { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) }
            verify(exactly = 0) { orderHoldRedisGateway.delete(any()) }
            verify(exactly = 0) { sseEventPublisher.publish(any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 4~6단계: soft-fail
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("4~6단계 — soft-fail (compareAndDelete·delete·SSE 실패 흡수)")
    inner class SoftFail {
        @Test
        @DisplayName("compareAndDelete 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `compareAndDelete failure does not propagate`() {
            every { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) } throws
                RuntimeException("compareAndDelete 실패")

            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)
        }

        @Test
        @DisplayName("compareAndDelete 실패 시에도 ORDER_HOLD DEL이 호출된다")
        fun `compareAndDelete failure still calls orderHold delete`() {
            every { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) } throws
                RuntimeException("compareAndDelete 실패")

            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { orderHoldRedisGateway.delete(orderId) }
        }

        @Test
        @DisplayName("compareAndDelete 실패 시에도 SSE가 발행된다")
        fun `compareAndDelete failure still publishes SSE`() {
            every { idempotencyRedisGateway.compareAndDelete(any(), any(), any()) } throws
                RuntimeException("compareAndDelete 실패")

            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }

        @Test
        @DisplayName("ORDER_HOLD DEL 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `orderHold delete failure does not propagate`() {
            every { orderHoldRedisGateway.delete(any()) } throws RuntimeException("Redis 불가")

            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)
        }

        @Test
        @DisplayName("ORDER_HOLD DEL 실패 시에도 SSE가 발행된다")
        fun `orderHold delete failure still publishes SSE`() {
            every { orderHoldRedisGateway.delete(any()) } throws RuntimeException("Redis 불가")

            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) { sseEventPublisher.publish(any(), any()) }
        }

        @Test
        @DisplayName("SSE 발행 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `SSE publish failure does not propagate`() {
            every { sseEventPublisher.publish(any(), any()) } throws RuntimeException("SSE 전송 실패")

            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // SSE 이벤트 이름 라우팅
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SSE 이벤트 이름 라우팅")
    inner class SseEventNameRouting {
        @Test
        @DisplayName("PAYMENT_TIMEOUT → SSE 이름 \"PAYMENT_TIMEOUT\"")
        fun `PAYMENT_TIMEOUT maps to PAYMENT_TIMEOUT SSE event name`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "PAYMENT_TIMEOUT" },
                )
            }
        }

        @Test
        @DisplayName("PAYMENT_FAILED → SSE 이름 \"ORDER_FAILED\"")
        fun `PAYMENT_FAILED maps to ORDER_FAILED SSE event name`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_FAILED)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = match { it.name == "ORDER_FAILED" },
                )
            }
        }

        @Test
        @DisplayName("모든 SSE 이벤트는 terminal=true이다")
        fun `all SSE events are terminal`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = any(),
                    event = match { it.terminal },
                )
            }
        }

        @Test
        @DisplayName("SSE 채널 키는 order 리소스와 orderIdStr로 구성된다")
        fun `SSE key uses order resource and orderIdStr`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verify(exactly = 1) {
                sseEventPublisher.publish(
                    key = orderSseKey,
                    event = any(),
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
        @DisplayName("컨텍스트 조회 → eventId 조회 → compensate → compareAndDelete → DEL → SSE 순서로 실행된다")
        fun `steps execute in declared order`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verifyOrder {
                reservationRepository.findIdempotencyContextByOrderId(orderIdStr)
                eventRepository.findById(internalEventId)
                stockRedisGateway.compensate(zoneId, orderId)
                idempotencyRedisGateway.compareAndDelete(userId, eventPublicId, orderId)
                orderHoldRedisGateway.delete(orderId)
                sseEventPublisher.publish(any(), any())
            }
        }

        @Test
        @DisplayName("compensate(3단계)는 compareAndDelete(4단계)보다 먼저 수행된다")
        fun `compensate precedes compareAndDelete`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verifyOrder {
                stockRedisGateway.compensate(any(), any())
                idempotencyRedisGateway.compareAndDelete(any(), any(), any())
            }
        }

        @Test
        @DisplayName("ORDER_HOLD DEL(5단계)은 SSE 발행(6단계)보다 먼저 수행된다")
        fun `orderHold delete precedes SSE publish`() {
            sut.release(orderIdStr, zoneId, ReleaseReason.PAYMENT_TIMEOUT)

            verifyOrder {
                orderHoldRedisGateway.delete(any())
                sseEventPublisher.publish(any(), any())
            }
        }
    }
}
