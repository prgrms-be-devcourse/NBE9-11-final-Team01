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
        val event =
            eventRepository.findByPublicId(request.eventId)
                ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

        val ticket =
            ticketRepository.findByTicketCode(request.ticketCode)
                ?: throw BusinessException(ErrorCode.TICKET_NOT_FOUND)

        val reservationEventId =
            verifyQuery.findReservationEventId(ticket.reservationId)
                ?: throw IllegalStateException(
                    "Reservation not found: ${ticket.reservationId}",
                )

        if (reservationEventId != event.id) {
            throw EventMismatchException()
        }

        val now = Instant.now()

        val updated =
            verifyQuery.markUsedIfIssued(
                request.ticketCode,
                now,
            )

        if (updated == 0) {
            throw TicketAlreadyUsedException()
        }

        return TicketVerifyResponse(
            ticketCode = request.ticketCode,
            eventId = request.eventId,
            status = "USED",
            usedAt = now.toString(),
            message = "입장 처리되었습니다.",
        )
    }
}
