package com.develop.snaptix.domain.ticket.service

import com.develop.snaptix.domain.ticket.repository.TicketRepository
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.global.realtime.subscribe.SseEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 발권(티켓 생성) 도메인 서비스.
 *
 * ### 책임
 * 1. `tickets` 행 INSERT ([TicketRepository.issue]) — 예외 전파 (critical path)
 * 2. SSE `TICKET_ISSUED` 발행 — soft-fail ([runCatching])
 *
 * ### 호출 계약
 * - 반드시 결제 확정(`CONFIRMED`) 전이가 완료된 이후 호출한다.
 * - DB INSERT 실패는 예외로 전파된다 — 호출자([MockPaymentWebhookService])가 핸들링한다.
 * - SSE 실패는 경고 로그만 남기고 발권 성공 응답에 영향을 주지 않는다.
 *
 * ### SSE payload
 * ```json
 * { "orderId": "<UUID>", "ticketCode": "<UUID>" }
 * ```
 * 프론트엔드는 `ticketCode` 를 QR 코드 렌더링에 사용한다.
 */
@Service
class TicketService(
    private val ticketRepository: TicketRepository,
    private val sseEventPublisher: SseEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 발권을 수행하고 SSE `TICKET_ISSUED` 이벤트를 발행한다.
     *
     * @param reservationId `reservations.id` FK — `tickets.reservation_id` 에 삽입됨
     * @param orderId       주문 UUID — SSE 채널 키 및 payload 구성에 사용됨
     */
    fun issue(
        reservationId: Long,
        orderId: UUID,
    ) {
        // 1. tickets 행 INSERT — 실패 시 예외 전파
        val ticketCode = ticketRepository.issue(reservationId)

        // 2. SSE TICKET_ISSUED 발행 (soft-fail)
        runCatching {
            sseEventPublisher.publish(
                key = SseChannelKey(SSE_RESOURCE, orderId.toString()),
                event =
                    SseEvent.terminal(
                        name = SSE_EVENT_TICKET_ISSUED,
                        data =
                            mapOf(
                                "orderId" to orderId.toString(),
                                "ticketCode" to ticketCode,
                            ),
                    ),
            )
        }.onFailure { ex ->
            logger.warn(ex) { "SSE TICKET_ISSUED 발행 실패 (무시): orderId=$orderId, ticketCode=$ticketCode" }
        }
    }

    private companion object {
        const val SSE_RESOURCE = "order"
        const val SSE_EVENT_TICKET_ISSUED = "TICKET_ISSUED"
    }
}
