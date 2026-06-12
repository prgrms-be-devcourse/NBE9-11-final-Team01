package com.develop.snaptix.domain.reservation.entity

import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

object ReservationsTable : Table("reservations") {
    val id = long("id").autoIncrement()

    // 202 응답 시점에 애플리케이션에서 UUID 생성, SSE URL 및 Redis 키에 사용
    val orderId = varchar("order_id", 36).uniqueIndex()

    val userId = long("user_id").references(UsersTable.id)
    val eventId = long("event_id").references(EventsTable.id)
    val zoneId = long("zone_id").references(ZonesTable.id)
    val amount = integer("amount")
    val mockPaymentKey = varchar("mock_payment_key", 36).nullable()
    val paidAt = timestamp("paid_at").nullable()

    // PENDING_PAYMENT, CONFIRMED, CANCELLED, RELEASED
    val status = varchar("status", 20)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

    init {
        // 1인 1이벤트 1매 멱등성 검사용
        index("idx_idempotency", false, userId, eventId)
        // PENDING_PAYMENT 타임아웃 배치 조회용
        index("idx_reconciliation", false, status, createdAt)
    }
}
