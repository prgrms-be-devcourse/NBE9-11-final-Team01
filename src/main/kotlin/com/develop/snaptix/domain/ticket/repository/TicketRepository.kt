package com.develop.snaptix.domain.ticket.repository

import com.develop.snaptix.domain.ticket.entity.TicketsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.Instant

/** 검표·테스트에서 사용하는 티켓 읽기 모델. */
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
    /** 현장 QR의 ticketCode(UUID)로 티켓을 조회한다(검표 기준). */
    fun findByTicketCode(ticketCode: String): TicketRecord? =
        transaction {
            TicketsTable
                .selectAll()
                .where { TicketsTable.ticketCode eq ticketCode }
                .singleOrNull()
                ?.toRecord()
        }

    fun findById(id: Long): TicketRecord? =
        transaction {
            TicketsTable
                .selectAll()
                .where { TicketsTable.id eq id }
                .singleOrNull()
                ?.toRecord()
        }

    /** 테스트 픽스처용 삽입. 생성된 id를 반환한다. */
    fun insert(
        reservationId: Long,
        ticketCode: String,
        status: String,
        issuedAt: Instant? = null,
        usedAt: Instant? = null,
    ): Long =
        transaction {
            TicketsTable.insert {
                it[TicketsTable.reservationId] = reservationId
                it[TicketsTable.ticketCode] = ticketCode
                it[TicketsTable.status] = status
                it[TicketsTable.issuedAt] = issuedAt
                it[TicketsTable.usedAt] = usedAt
            } get TicketsTable.id
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
