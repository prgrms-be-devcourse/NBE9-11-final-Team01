package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.api.dto.OrderStatus
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.repository.OrderOwnerStore
import com.develop.snaptix.domain.reservation.repository.ReservationQuery
import com.develop.snaptix.domain.reservation.repository.ReservationView
import com.develop.snaptix.domain.ticket.repository.TicketQuery
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@DisplayName("OrderQueryService 단위 테스트")
class OrderQueryServiceTest {
    // ── 의존성 mock ─────────────────────────────────────────────────────────
    private val reservationQuery = mockk<ReservationQuery>()
    private val orderOwnerStore = mockk<OrderOwnerStore>()
    private val ticketQuery = mockk<TicketQuery>()

    private lateinit var sut: OrderQueryService

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────
    private val userId = 1L
    private val orderId = "test-order-id-1234"
    private val holdDuration: Duration = Duration.ofMinutes(5)
    private val ticketCode = "ticket-uuid-1234"

    @BeforeEach
    fun setUp() {
        sut =
            OrderQueryService(
                reservationQuery = reservationQuery,
                orderOwnerStore = orderOwnerStore,
                ticketQuery = ticketQuery,
                redisTtlProperties = RedisTtlProperties(orderHold = holdDuration),
            )

        // 기본 해피 패스 스텁 (CONFIRMED 흐름)
        every { reservationQuery.findByOrderId(any()) } returns reservationView(status = ReservationStatus.CONFIRMED)
        every { ticketQuery.findTicketCodeByOrderId(any()) } returns ticketCode
        every { orderOwnerStore.findOwnerUserId(any()) } returns userId
    }

    // ════════════════════════════════════════════════════════════════════════
    // 예약 행 없음 (PENDING 처리 전)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("예약 행 없음 — owner 키 기반 분기")
    inner class NoReservationRow {
        @BeforeEach
        fun stubNoReservation() {
            every { reservationQuery.findByOrderId(orderId) } returns null
        }

        @Test
        @DisplayName("owner 키 존재 + 소유자 일치 → PENDING 반환")
        fun `owner key exists and matches userId - returns PENDING`() {
            every { orderOwnerStore.findOwnerUserId(orderId) } returns userId

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.PENDING)
            assertThat(response.message).isNotBlank()
        }

        @Test
        @DisplayName("owner 키 존재 + 소유자 불일치 → 403 ORDER_ACCESS_DENIED")
        fun `owner key exists but userId mismatch - throws 403`() {
            every { orderOwnerStore.findOwnerUserId(orderId) } returns userId + 1

            assertThatThrownBy { sut.getStatus(userId, orderId) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_ACCESS_DENIED)
        }

        @Test
        @DisplayName("owner 키도 없음 → 404 ORDER_NOT_FOUND")
        fun `no reservation and no owner key - throws 404`() {
            every { orderOwnerStore.findOwnerUserId(orderId) } returns null

            assertThatThrownBy { sut.getStatus(userId, orderId) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 예약 행 존재 — IDOR 방어
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("예약 행 존재 — IDOR 방어")
    inner class ReservationRowExists {
        @Test
        @DisplayName("예약 행의 userId 불일치 → 403 ORDER_ACCESS_DENIED")
        fun `reservation userId mismatch - throws 403`() {
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.PENDING_PAYMENT, ownerId = userId + 99)

            assertThatThrownBy { sut.getStatus(userId, orderId) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_ACCESS_DENIED)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PENDING_PAYMENT 상태 판정
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PENDING_PAYMENT 상태 판정")
    inner class PendingPaymentResolution {
        @Test
        @DisplayName("홀드 윈도우 내(createdAt + 5분 > now) → READY_TO_PAY + paymentDeadline")
        fun `within hold window - returns READY_TO_PAY with paymentDeadline`() {
            val createdAt = Instant.now().minus(Duration.ofMinutes(1)) // 1분 경과, 4분 남음
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.PENDING_PAYMENT, createdAt = createdAt)

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.READY_TO_PAY)
            assertThat(response.paymentDeadline).isAfter(Instant.now())
            assertThat(response.paymentDeadline).isEqualTo(createdAt.plus(holdDuration))
        }

        @Test
        @DisplayName("홀드 만료(createdAt + 5분 < now) → PENDING (곧 RELEASED 예정)")
        fun `hold window expired - returns PENDING`() {
            val createdAt = Instant.now().minus(Duration.ofMinutes(6)) // 6분 경과, 만료
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.PENDING_PAYMENT, createdAt = createdAt)

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.PENDING)
            assertThat(response.message).isNotBlank()
            assertThat(response.paymentDeadline).isNull()
        }

        @Test
        @DisplayName("ORDER_HOLD 키 소실돼도 윈도우 내면 READY_TO_PAY 재구성 (결정 D4)")
        fun `hold key absent but within window - reconstructs READY_TO_PAY`() {
            // ORDER_HOLD Redis 키가 없어도 createdAt 윈도우로 판정 → orderOwnerStore 미참조
            val createdAt = Instant.now().minus(Duration.ofSeconds(30))
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.PENDING_PAYMENT, createdAt = createdAt)

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.READY_TO_PAY)
            assertThat(response.paymentDeadline).isNotNull()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 터미널 상태 판정
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("터미널 상태 판정")
    inner class TerminalStatus {
        @Test
        @DisplayName("CONFIRMED → CONFIRMED + ticketCode")
        fun `CONFIRMED status - returns CONFIRMED with ticketCode`() {
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.CONFIRMED)
            every { ticketQuery.findTicketCodeByOrderId(orderId) } returns ticketCode

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(response.ticketCode).isEqualTo(ticketCode)
        }

        @Test
        @DisplayName("CONFIRMED + 티켓 미발급 → CONFIRMED + ticketCode=null (null 안전)")
        fun `CONFIRMED but ticket not yet issued - returns CONFIRMED with null ticketCode`() {
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.CONFIRMED)
            every { ticketQuery.findTicketCodeByOrderId(orderId) } returns null

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(response.ticketCode).isNull()
        }

        @Test
        @DisplayName("CANCELLED → FAILED")
        fun `CANCELLED status - returns FAILED`() {
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.CANCELLED)

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.FAILED)
        }

        @Test
        @DisplayName("RELEASED → EXPIRED")
        fun `RELEASED status - returns EXPIRED`() {
            every { reservationQuery.findByOrderId(orderId) } returns
                reservationView(status = ReservationStatus.RELEASED)

            val response = sut.getStatus(userId, orderId)

            assertThat(response.status).isEqualTo(OrderStatus.EXPIRED)
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun reservationView(
        status: ReservationStatus,
        ownerId: Long = userId,
        createdAt: Instant = Instant.now(),
    ) = ReservationView(userId = ownerId, status = status, createdAt = createdAt)
}
