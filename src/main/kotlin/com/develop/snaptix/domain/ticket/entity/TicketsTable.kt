package com.develop.snaptix.domain.ticket.entity

import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

object TicketsTable : Table("tickets") {
    val id = long("id").autoIncrement()
    val reservationId = long("reservation_id").uniqueIndex().references(ReservationsTable.id)

    // QR 스캔 및 API ticketId로 사용되는 외부 식별자
    val ticketCode = varchar("ticket_code", 36).uniqueIndex()

    // ISSUED, USED
    val status = varchar("status", 10)

    // 현장 체크인 중복 입장 방지용 낙관적 락
    val version = integer("version").default(0)
    val issuedAt = timestamp("issued_at").nullable()

    // 🆕 작업 명세서 D3: 입장(USED) 처리 시각. 검표 API 응답의 usedAt 용.
    val usedAt = timestamp("used_at").nullable()

    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)
}
