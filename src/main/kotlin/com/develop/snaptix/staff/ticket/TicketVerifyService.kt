package com.develop.snaptix.staff.ticket

import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.ticket.repository.TicketRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.staff.ticket.dto.TicketVerifyRequest
import com.develop.snaptix.staff.ticket.dto.TicketVerifyResponse
import com.develop.snaptix.staff.ticket.error.EventMismatchException
import com.develop.snaptix.staff.ticket.error.TicketAlreadyUsedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class TicketVerifyService(
    private val eventRepository: EventRepository,
    private val ticketRepository: TicketRepository,
    private val verifyQuery: TicketVerifyQuery,
) {
    @Transactional
    fun verify(request: TicketVerifyRequest): TicketVerifyResponse {
        val event = getEvent(request.eventId)

        val ticket = getTicket(request.ticketCode)

        validateEventMatch(
            reservationId = ticket.reservationId,
            eventId = event.id,
        )

        val now = Instant.now()

        validateTicketUsage(
            ticketCode = request.ticketCode,
            now = now,
        )

        return TicketVerifyResponse(
            ticketCode = request.ticketCode,
            eventId = request.eventId,
            status = "USED",
            usedAt = now.toString(),
            message = "입장 처리되었습니다.",
        )
    }

    private fun getEvent(eventId: String) = eventRepository.findByPublicId(eventId)
        ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

    private fun getTicket(ticketCode: String) = ticketRepository.findByTicketCode(ticketCode)
        ?: throw BusinessException(ErrorCode.TICKET_NOT_FOUND)

    private fun validateEventMatch(
        reservationId: Long,
        eventId: Long,
    ) {
        val reservationEventId =
            verifyQuery.findReservationEventId(reservationId)
                ?: error("Reservation not found: $reservationId")

        if (reservationEventId != eventId) {
            throw EventMismatchException()
        }
    }

    private fun validateTicketUsage(
        ticketCode: String,
        now: Instant,
    ) {
        val updated =
            verifyQuery.markUsedIfIssued(
                ticketCode = ticketCode,
                now = now,
            )

        if (updated == 0) {
            throw TicketAlreadyUsedException()
        }
    }
}
