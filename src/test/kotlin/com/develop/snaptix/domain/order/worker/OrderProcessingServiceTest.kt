package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.worker.port.CompensationPort
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.domain.reservation.repository.ReservationView
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.redis.gateway.DecreaseResult
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@DisplayName("OrderProcessingService 단위 테스트")
class OrderProcessingServiceTest {
    // ── 의존성 mock ─────────────────────────────────────────────────────────────
    private val stockRedisGateway = mockk<StockRedisGateway>()
    private val reservationRepository = mockk<ReservationRepository>()
    private val orderHoldRedisGateway = mockk<OrderHoldRedisGateway>(relaxed = true)
    private val idempotencyRedisGateway = mockk<IdempotencyRedisGateway>(relaxed = true)
    private val sseConnectionManager = mockk<SseConnectionManager>(relaxed = true)
    private val compensationPort = mockk<CompensationPort>(relaxed = true)

    private lateinit var sut: OrderProcessingService

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────
    private val orderId = UUID.randomUUID()
    private val userId = 1L
    private val eventPublicId = UUID.randomUUID()
    private val zoneId = 10L
    private val internalEventId = 42L

    private val message =
        OrderMessage(
            orderId = orderId,
            userId = userId,
            eventId = eventPublicId,
            zoneId = zoneId,
        )

    /** SseChannelKey 는 data class — 동등 비교로 verify 에서 바로 사용한다. */
    private val orderSseKey = SseChannelKey(resource = "order", id = orderId.toString())

    @BeforeEach
    fun setUp() {
        sut =
            OrderProcessingService(
                stockRedisGateway = stockRedisGateway,
                reservationRepository = reservationRepository,
                orderHoldRedisGateway = orderHoldRedisGateway,
                idempotencyRedisGateway = idempotencyRedisGateway,
                sseConnectionManager = sseConnectionManager,
                compensationPort = compensationPort,
            )

        // 정상 흐름 기본 스텁 — 각 중첩 클래스에서 필요한 부분만 재정의한다
        every { reservationRepository.findByOrderId(any()) } returns null
        every { reservationRepository.findInternalEventId(zoneId) } returns internalEventId
        every { reservationRepository.existsActiveForUserAndEvent(any(), any()) } returns false
        every { stockRedisGateway.decreaseAndClaim(any(), any()) } returns DecreaseResult.OK
        every { reservationRepository.insertPending(any(), any(), any(), any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    private fun reservationView(status: ReservationStatus) =
        ReservationView(userId = userId, status = status, createdAt = Instant.now())

    // ════════════════════════════════════════════════════════════════════════════
    // 정상 흐름
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("정상 흐름")
    inner class HappyPath {
        @Test
        @DisplayName("모든 단계 통과 시 INSERT, ORDER_HOLD, 멱등 재앵커링, READY_TO_PAY ongoing SSE 가 호출된다")
        fun `all steps pass — INSERT hold reanchor and READY_TO_PAY SSE are called`() {
            sut.process(message)

            verify(exactly = 1) {
                reservationRepository.insertPending(
                    orderId = orderId.toString(),
                    userId = userId,
                    internalEventId = internalEventId,
                    zoneId = zoneId,
                )
            }
            verify(exactly = 1) { orderHoldRedisGateway.create(orderId) }
            verify(exactly = 1) { idempotencyRedisGateway.reanchor(userId, eventPublicId) }
            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "READY_TO_PAY" && !it.terminal },
                )
            }
        }

        @Test
        @DisplayName("정상 흐름에서 compensateIfLeaked 는 호출되지 않는다")
        fun `compensateIfLeaked is not called on success`() {
            sut.process(message)
            verify(exactly = 0) { compensationPort.compensateIfLeaked(any(), any()) }
        }

        @Test
        @DisplayName("findInternalEventId 에 message.zoneId 가 전달된다")
        fun `zoneId is passed to findInternalEventId`() {
            sut.process(message)
            verify(exactly = 1) { reservationRepository.findInternalEventId(zoneId) }
        }

        @Test
        @DisplayName("decreaseAndClaim 에 message.zoneId 와 message.orderId 가 전달된다")
        fun `zoneId and orderId are passed to decreaseAndClaim`() {
            sut.process(message)
            verify(exactly = 1) { stockRedisGateway.decreaseAndClaim(zoneId, orderId) }
        }

        @Test
        @DisplayName("insertPending 에 내부 eventId(Long) 가 전달된다 — UUID 가 아님")
        fun `insertPending receives internalEventId not the public UUID`() {
            sut.process(message)
            verify(exactly = 1) {
                reservationRepository.insertPending(
                    orderId = any(),
                    userId = any(),
                    internalEventId = internalEventId,
                    zoneId = any(),
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1단계: 재배달 처리 (DB 행 존재)
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("1단계 — 재배달 처리 (DB 행 존재)")
    inner class RedeliveryHandling {
        @Test
        @DisplayName("PENDING_PAYMENT 상태 행이 있으면 READY_TO_PAY ongoing SSE 를 재발행하고 즉시 반환한다")
        fun `PENDING_PAYMENT redelivery dispatches READY_TO_PAY ongoing SSE`() {
            every { reservationRepository.findByOrderId(orderId.toString()) } returns
                reservationView(ReservationStatus.PENDING_PAYMENT)

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "READY_TO_PAY" && !it.terminal },
                )
            }
        }

        @Test
        @DisplayName("CONFIRMED 상태 행이 있으면 TICKET_ISSUED terminal SSE 를 재발행한다")
        fun `CONFIRMED redelivery dispatches TICKET_ISSUED terminal SSE`() {
            every { reservationRepository.findByOrderId(orderId.toString()) } returns
                reservationView(ReservationStatus.CONFIRMED)

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "TICKET_ISSUED" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("CANCELLED 상태 행이 있으면 ORDER_FAILED terminal SSE 를 재발행한다")
        fun `CANCELLED redelivery dispatches ORDER_FAILED terminal SSE`() {
            every { reservationRepository.findByOrderId(orderId.toString()) } returns
                reservationView(ReservationStatus.CANCELLED)

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "ORDER_FAILED" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("RELEASED 상태 행이 있으면 ORDER_FAILED terminal SSE 를 재발행한다")
        fun `RELEASED redelivery dispatches ORDER_FAILED terminal SSE`() {
            every { reservationRepository.findByOrderId(orderId.toString()) } returns
                reservationView(ReservationStatus.RELEASED)

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "ORDER_FAILED" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("재배달 시 Redis 차감(decreaseAndClaim)을 수행하지 않는다 — 이중 차감 방지")
        fun `redelivery does not call decreaseAndClaim`() {
            every { reservationRepository.findByOrderId(orderId.toString()) } returns
                reservationView(ReservationStatus.PENDING_PAYMENT)

            sut.process(message)

            verify(exactly = 0) { stockRedisGateway.decreaseAndClaim(any(), any()) }
        }

        @Test
        @DisplayName("재배달 시 INSERT 를 수행하지 않는다")
        fun `redelivery does not call insertPending`() {
            every { reservationRepository.findByOrderId(orderId.toString()) } returns
                reservationView(ReservationStatus.CONFIRMED)

            sut.process(message)

            verify(exactly = 0) { reservationRepository.insertPending(any(), any(), any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 2단계: eventId 변환 실패 (터미널)
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("2단계 — eventId 변환 실패 (zoneId 미매핑)")
    inner class EventIdResolution {
        @Test
        @DisplayName("zoneId 에 해당하는 event 가 없으면 IllegalArgumentException(터미널)을 던진다")
        fun `unknown zoneId throws IllegalArgumentException`() {
            every { reservationRepository.findInternalEventId(zoneId) } returns null

            assertThatThrownBy { sut.process(message) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        @DisplayName("zoneId 미매핑 시 1인1매 검사, 차감, INSERT 를 수행하지 않는다")
        fun `unknown zoneId skips all subsequent steps`() {
            every { reservationRepository.findInternalEventId(zoneId) } returns null

            runCatching { sut.process(message) }

            verify(exactly = 0) { reservationRepository.existsActiveForUserAndEvent(any(), any()) }
            verify(exactly = 0) { stockRedisGateway.decreaseAndClaim(any(), any()) }
            verify(exactly = 0) { reservationRepository.insertPending(any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("zoneId 미매핑 시 SSE 발행을 수행하지 않는다")
        fun `unknown zoneId does not dispatch SSE`() {
            every { reservationRepository.findInternalEventId(zoneId) } returns null

            runCatching { sut.process(message) }

            verify(exactly = 0) { sseConnectionManager.dispatch(any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 3단계: 1인1매 선검사
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("3단계 — 1인1매 선검사")
    inner class DuplicateActiveCheck {
        @Test
        @DisplayName("동일 (userId, internalEventId) 유효 점유가 있으면 ORDER_FAILED terminal SSE 를 발행하고 정상 반환한다")
        fun `duplicate active reservation dispatches ORDER_FAILED terminal SSE`() {
            every { reservationRepository.existsActiveForUserAndEvent(userId, internalEventId) } returns true

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "ORDER_FAILED" && it.terminal },
                )
            }
        }

        @Test
        @DisplayName("1인1매 위반 시 Redis 차감을 수행하지 않는다 — 불필요한 재고 감소 방지")
        fun `duplicate active check skips decreaseAndClaim`() {
            every { reservationRepository.existsActiveForUserAndEvent(userId, internalEventId) } returns true

            sut.process(message)

            verify(exactly = 0) { stockRedisGateway.decreaseAndClaim(any(), any()) }
        }

        @Test
        @DisplayName("1인1매 위반 시 INSERT 와 보상을 수행하지 않는다")
        fun `duplicate active check skips insertPending and compensate`() {
            every { reservationRepository.existsActiveForUserAndEvent(userId, internalEventId) } returns true

            sut.process(message)

            verify(exactly = 0) { reservationRepository.insertPending(any(), any(), any(), any()) }
            verify(exactly = 0) { compensationPort.compensateIfLeaked(any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 4단계: Redis 권위 관문 (decreaseAndClaim)
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("4단계 — Redis 권위 관문 (decreaseAndClaim)")
    inner class StockDecreaseGate {
        @Nested
        @DisplayName("SOLD_OUT")
        inner class SoldOut {
            @BeforeEach
            fun stub() {
                every { stockRedisGateway.decreaseAndClaim(any(), any()) } returns DecreaseResult.SOLD_OUT
            }

            @Test
            @DisplayName("SOLD_OUT 이면 ORDER_FAILED terminal SSE 를 발행하고 정상 반환한다 (XACK)")
            fun `SOLD_OUT dispatches ORDER_FAILED terminal SSE and returns normally`() {
                sut.process(message)

                verify(exactly = 1) {
                    sseConnectionManager.dispatch(
                        orderSseKey,
                        match { it.name == "ORDER_FAILED" && it.terminal },
                    )
                }
            }

            @Test
            @DisplayName("SOLD_OUT 이면 INSERT 를 수행하지 않는다")
            fun `SOLD_OUT does not call insertPending`() {
                sut.process(message)
                verify(exactly = 0) { reservationRepository.insertPending(any(), any(), any(), any()) }
            }

            @Test
            @DisplayName("SOLD_OUT 이면 ORDER_HOLD 와 멱등 재앵커링을 수행하지 않는다")
            fun `SOLD_OUT does not set hold or reanchor`() {
                sut.process(message)
                verify(exactly = 0) { orderHoldRedisGateway.create(any()) }
                verify(exactly = 0) { idempotencyRedisGateway.reanchor(any(), any()) }
            }
        }

        @Nested
        @DisplayName("ALREADY")
        inner class Already {
            @BeforeEach
            fun stub() {
                every { stockRedisGateway.decreaseAndClaim(any(), any()) } returns DecreaseResult.ALREADY
            }

            @Test
            @DisplayName("ALREADY 이면 IllegalStateException(비터미널)을 던진다 — PEL 잔존")
            fun `ALREADY throws IllegalStateException as non-terminal`() {
                assertThatThrownBy { sut.process(message) }
                    .isInstanceOf(IllegalStateException::class.java)
            }

            @Test
            @DisplayName("ALREADY 이면 INSERT 를 수행하지 않는다")
            fun `ALREADY does not call insertPending`() {
                runCatching { sut.process(message) }
                verify(exactly = 0) { reservationRepository.insertPending(any(), any(), any(), any()) }
            }

            @Test
            @DisplayName("ALREADY 이면 보상을 수행하지 않는다")
            fun `ALREADY does not call compensateIfLeaked`() {
                runCatching { sut.process(message) }
                verify(exactly = 0) { compensationPort.compensateIfLeaked(any(), any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 5단계: DB INSERT 실패
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("5단계 — DB INSERT 실패")
    inner class InsertFailure {
        // ExposedSQLException(cause, contexts, transaction) 는 Transaction 이 필요해
        // 단위 테스트에서 직접 생성 불가. relaxed = true 는 Kotlin 메타데이터 리플렉션 중
        // StackOverflowError 를 유발하므로, Logback 이 Throwable 포맷 시 호출하는
        // 메서드를 모두 명시 스텁한다.
        private val dbException =
            mockk<ExposedSQLException>().also {
                every { it.message } returns "test DB error"
                every { it.localizedMessage } returns "test DB error"
                every { it.cause } returns null
                every { it.stackTrace } returns emptyArray()
                every { it.suppressed } returns emptyArray<Throwable>()
            }

        @BeforeEach
        fun stubInsertFailure() {
            every { reservationRepository.insertPending(any(), any(), any(), any()) } throws dbException
        }

        @Test
        @DisplayName("ExposedSQLException 발생 시 compensateIfLeaked(orderId, zoneId) 를 호출한다")
        fun `compensateIfLeaked is called with orderId and zoneId on insert failure`() {
            runCatching { sut.process(message) }
            verify(exactly = 1) { compensationPort.compensateIfLeaked(orderId, zoneId) }
        }

        @Test
        @DisplayName("ExposedSQLException 은 재전파된다 — 비터미널(PEL 잔존)")
        fun `ExposedSQLException is rethrown as non-terminal`() {
            assertThatThrownBy { sut.process(message) }
                .isInstanceOf(ExposedSQLException::class.java)
        }

        @Test
        @DisplayName("INSERT 실패 시 ORDER_HOLD, 멱등 재앵커링, READY_TO_PAY SSE 를 수행하지 않는다")
        fun `post-processing is skipped entirely when insert fails`() {
            runCatching { sut.process(message) }

            verify(exactly = 0) { orderHoldRedisGateway.create(any()) }
            verify(exactly = 0) { idempotencyRedisGateway.reanchor(any(), any()) }
            verify(exactly = 0) {
                sseConnectionManager.dispatch(any(), match { it.name == "READY_TO_PAY" })
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 6단계: 사후 처리 best-effort
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("6단계 — 사후 처리 best-effort (INSERT 성공 후 Redis·SSE 실패)")
    inner class PostProcessingBestEffort {
        @Test
        @DisplayName("ORDER_HOLD SET 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `order hold failure does not propagate`() {
            every { orderHoldRedisGateway.create(any()) } throws RuntimeException("Redis 불가")
            sut.process(message) // 예외 없이 반환 — assertion 불필요
        }

        @Test
        @DisplayName("ORDER_HOLD SET 실패 시에도 멱등 재앵커링을 시도한다")
        fun `idempotency reanchor is attempted even if order hold fails`() {
            every { orderHoldRedisGateway.create(any()) } throws RuntimeException("Redis 불가")

            sut.process(message)

            verify(exactly = 1) { idempotencyRedisGateway.reanchor(userId, eventPublicId) }
        }

        @Test
        @DisplayName("ORDER_HOLD SET 실패 시에도 READY_TO_PAY SSE 를 발행한다")
        fun `READY_TO_PAY SSE dispatched even if order hold fails`() {
            every { orderHoldRedisGateway.create(any()) } throws RuntimeException("Redis 불가")

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "READY_TO_PAY" && !it.terminal },
                )
            }
        }

        @Test
        @DisplayName("멱등 재앵커링 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `reanchor failure does not propagate`() {
            every { idempotencyRedisGateway.reanchor(any(), any()) } throws RuntimeException("EXPIRE 실패")
            sut.process(message)
        }

        @Test
        @DisplayName("멱등 재앵커링 실패 시에도 READY_TO_PAY SSE 를 발행한다")
        fun `SSE dispatched even if reanchor fails`() {
            every { idempotencyRedisGateway.reanchor(any(), any()) } throws RuntimeException("EXPIRE 실패")

            sut.process(message)

            verify(exactly = 1) {
                sseConnectionManager.dispatch(
                    orderSseKey,
                    match { it.name == "READY_TO_PAY" && !it.terminal },
                )
            }
        }

        @Test
        @DisplayName("READY_TO_PAY SSE 발행 실패 시 예외를 전파하지 않고 정상 반환한다")
        fun `SSE dispatch failure does not propagate`() {
            every { sseConnectionManager.dispatch(any(), any()) } throws RuntimeException("SSE 전송 실패")
            sut.process(message)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 단계 순서 보장
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("단계 순서 보장")
    inner class StepOrdering {
        @Test
        @DisplayName("멱등 선검사 → eventId 변환 → 1인1매 → 차감 → INSERT 순서로 수행된다")
        fun `main processing steps execute in declared order`() {
            sut.process(message)

            verifyOrder {
                reservationRepository.findByOrderId(orderId.toString())
                reservationRepository.findInternalEventId(zoneId)
                reservationRepository.existsActiveForUserAndEvent(userId, internalEventId)
                stockRedisGateway.decreaseAndClaim(zoneId, orderId)
                reservationRepository.insertPending(any(), any(), any(), any())
            }
        }

        @Test
        @DisplayName("INSERT 성공 후 ORDER_HOLD → 멱등 재앵커링 → READY_TO_PAY SSE 순서로 사후 처리가 수행된다")
        fun `post-processing steps execute in declared order`() {
            sut.process(message)

            verifyOrder {
                orderHoldRedisGateway.create(orderId)
                idempotencyRedisGateway.reanchor(userId, eventPublicId)
                sseConnectionManager.dispatch(any(), match { it.name == "READY_TO_PAY" })
            }
        }

        @Test
        @DisplayName("1인1매 선검사(3단계)가 Redis 차감(4단계)보다 먼저 수행된다")
        fun `duplicate check precedes decreaseAndClaim`() {
            sut.process(message)

            verifyOrder {
                reservationRepository.existsActiveForUserAndEvent(any(), any())
                stockRedisGateway.decreaseAndClaim(any(), any())
            }
        }
    }
}
