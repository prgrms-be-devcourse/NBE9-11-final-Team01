package com.develop.snaptix.domain.ticket.service

import com.develop.snaptix.domain.ticket.repository.TicketRepository
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.subscribe.SseEventPublisher
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [TicketService] 단위 테스트.
 *
 * ## 전략
 * - [TicketRepository] / [SseEventPublisher] 는 MockK 로 대체 — 비즈니스 로직만 검증한다.
 * - DB INSERT([TicketRepository.issue]) 실패는 예외를 전파한다 (critical path).
 * - SSE 발행 실패는 예외를 전파하지 않는다 (soft-fail).
 *
 * ## 커버하는 AC
 * 1. ticketRepository.issue(reservationId) 가 호출된다
 * 2. SSE TICKET_ISSUED 에 ticketCode 가 포함된다
 * 3. SSE 채널 키는 "order" 리소스와 orderId 로 구성된다
 * 4. SSE 실패 시 예외를 전파하지 않는다
 * 5. DB INSERT 실패 시 예외를 전파한다
 */
@DisplayName("TicketService 단위 테스트")
class TicketServiceTest {
    private val ticketRepository = mockk<TicketRepository>()
    private val sseEventPublisher = mockk<SseEventPublisher>(relaxed = true)

    private lateinit var sut: TicketService

    private val reservationId = 1L
    private val orderId = UUID.randomUUID()
    private val ticketCode = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        sut =
            TicketService(
                ticketRepository = ticketRepository,
                sseEventPublisher = sseEventPublisher,
            )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 정상 발권 흐름
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("정상 발권 흐름")
    inner class HappyPath {
        @BeforeEach
        fun stub() {
            every { ticketRepository.issue(reservationId) } returns ticketCode
        }

        @Test
        @DisplayName("ticketRepository.issue(reservationId)가 호출된다")
        fun `calls ticketRepository issue with reservationId`() {
            sut.issue(reservationId, orderId)

            verify(exactly = 1) { ticketRepository.issue(reservationId) }
        }

        @Test
        @DisplayName("SSE TICKET_ISSUED 이벤트에 ticketCode가 포함된다")
        fun `SSE event contains ticketCode`() {
            val eventSlot = slot<com.develop.snaptix.global.realtime.SseEvent>()
            justRun { sseEventPublisher.publish(any(), capture(eventSlot)) }

            sut.issue(reservationId, orderId)

            // SseEvent.data는 Any 타입이므로 Map으로 캐스팅
            val data = eventSlot.captured.data as Map<*, *>
            assertThat(data["ticketCode"]).isEqualTo(ticketCode)
        }

        @Test
        @DisplayName("SSE TICKET_ISSUED 이벤트에 orderId가 포함된다")
        fun `SSE event contains orderId`() {
            val eventSlot = slot<com.develop.snaptix.global.realtime.SseEvent>()
            justRun { sseEventPublisher.publish(any(), capture(eventSlot)) }

            sut.issue(reservationId, orderId)

            val data = eventSlot.captured.data as Map<*, *>
            assertThat(data["orderId"]).isEqualTo(orderId.toString())
        }

        @Test
        @DisplayName("SSE 이벤트 이름은 TICKET_ISSUED이다")
        fun `SSE event name is TICKET_ISSUED`() {
            val eventSlot = slot<com.develop.snaptix.global.realtime.SseEvent>()
            justRun { sseEventPublisher.publish(any(), capture(eventSlot)) }

            sut.issue(reservationId, orderId)

            assertThat(eventSlot.captured.name).isEqualTo("TICKET_ISSUED")
        }

        @Test
        @DisplayName("SSE 채널 키는 order 리소스와 orderId 문자열로 구성된다")
        fun `SSE key uses order resource and orderId string`() {
            val keySlot = slot<SseChannelKey>()
            justRun { sseEventPublisher.publish(capture(keySlot), any()) }

            sut.issue(reservationId, orderId)

            assertThat(keySlot.captured.resource).isEqualTo("order")
            assertThat(keySlot.captured.id).isEqualTo(orderId.toString())
        }

        @Test
        @DisplayName("SSE 이벤트는 terminal=true이다")
        fun `SSE event is terminal`() {
            val eventSlot = slot<com.develop.snaptix.global.realtime.SseEvent>()
            justRun { sseEventPublisher.publish(any(), capture(eventSlot)) }

            sut.issue(reservationId, orderId)

            assertThat(eventSlot.captured.terminal).isTrue()
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // SSE soft-fail
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SSE soft-fail")
    inner class SseSoftFail {
        @BeforeEach
        fun stub() {
            every { ticketRepository.issue(reservationId) } returns ticketCode
        }

        @Test
        @DisplayName("SSE 발행 실패 시 예외를 전파하지 않는다")
        fun `SSE failure does not propagate`() {
            every { sseEventPublisher.publish(any(), any()) } throws RuntimeException("SSE 연결 실패")

            sut.issue(reservationId, orderId) // 예외 없이 반환 자체가 검증
        }

        @Test
        @DisplayName("SSE 실패 시에도 DB INSERT는 이미 완료된 상태다 (순서 보장)")
        fun `DB insert completes before SSE even if SSE fails`() {
            every { sseEventPublisher.publish(any(), any()) } throws RuntimeException("SSE 연결 실패")

            sut.issue(reservationId, orderId)

            // ticketRepository.issue()는 SSE 실패와 무관하게 이미 호출됨
            verify(exactly = 1) { ticketRepository.issue(reservationId) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // DB INSERT 실패 — 예외 전파
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DB INSERT 실패 — 예외 전파 (critical path)")
    inner class DbInsertFailure {
        @Test
        @DisplayName("ticketRepository.issue() 실패 시 예외를 전파한다")
        fun `DB insert failure propagates exception`() {
            every { ticketRepository.issue(reservationId) } throws RuntimeException("DB 연결 실패")

            assertThatThrownBy { sut.issue(reservationId, orderId) }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("DB 연결 실패")
        }

        @Test
        @DisplayName("DB INSERT 실패 시 SSE를 발행하지 않는다")
        fun `SSE is not published when DB insert fails`() {
            every { ticketRepository.issue(reservationId) } throws RuntimeException("DB 연결 실패")

            runCatching { sut.issue(reservationId, orderId) }

            verify(exactly = 0) { sseEventPublisher.publish(any(), any()) }
        }
    }
}
