package com.develop.snaptix.domain.ticket.repository

import com.develop.snaptix.domain.ticket.entity.TicketsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant

data class TicketRecord(
    val id: Long,
    val reservationId: Long,
    val ticketCode: String,
    val status: String,
    val version: Int,
    val issuedAt: Instant?,
    val usedAt: Instant?,
)

@Repository
class TicketRepository {
    fun findByTicketCode(ticketCode: String): TicketRecord? =
        transaction {
            TicketsTable.selectAll()
                .where { TicketsTable.ticketCode eq ticketCode }
                .singleOrNull()
                ?.toRecord()
        }

    fun findById(id: Long): TicketRecord? =
        transaction {
            TicketsTable.selectAll()
                .where { TicketsTable.id eq id }
                .singleOrNull()
                ?.toRecord()
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
        }
    }

    private fun ResultRow.toRecord() =
        TicketRecord(
            id = this[TicketsTable.id],
            reservationId = this[TicketsTable.reservationId],
            ticketCode = this[TicketsTable.ticketCode],
            status = this[TicketsTable.status],
            version = this[TicketsTable.version],
            issuedAt = this[TicketsTable.issuedAt],
            usedAt = this[TicketsTable.usedAt],
        )
}
