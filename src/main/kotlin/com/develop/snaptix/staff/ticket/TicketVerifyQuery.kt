package com.develop.snaptix.staff.ticket

import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.ticket.entity.TicketsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TicketVerifyQuery {

    fun findReservationEventId(
        reservationId: Long,
    ): Long? = transaction {
        ReservationsTable
            .selectAll()
            .where { ReservationsTable.id eq reservationId }
            .singleOrNull()
            ?.get(ReservationsTable.eventId)
    }

    fun markUsedIfIssued(
        ticketCode: String,
        now: Instant,
    ): Int = transaction {
        TicketsTable.update({
            (TicketsTable.ticketCode eq ticketCode) and
                (TicketsTable.status eq "ISSUED")
        }) {
            it[status] = "USED"
            it[usedAt] = now
            it[updatedAt] = now
        }
    }
}