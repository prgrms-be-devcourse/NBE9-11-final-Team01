package com.develop.snaptix.domain.payment.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.Instant

data class PaymentReservation(
    val id: Long,
    val orderId: String,
    val userId: Long,
    val eventId: Long,
    val zoneId: Long,
    val status: ReservationStatus,
    val createdAt: Instant,
)

@Repository
class PaymentReservationRepository {
    fun findByOrderId(orderId: String): PaymentReservation? = transaction {
        ReservationsTable
            .select(
                ReservationsTable.id,
                ReservationsTable.orderId,
                ReservationsTable.userId,
                ReservationsTable.eventId,
                ReservationsTable.zoneId,
                ReservationsTable.status,
                ReservationsTable.createdAt,
            ).where { ReservationsTable.orderId eq orderId }
            .limit(1)
            .map {
                PaymentReservation(
                    id = it[ReservationsTable.id],
                    orderId = it[ReservationsTable.orderId],
                    userId = it[ReservationsTable.userId],
                    eventId = it[ReservationsTable.eventId],
                    zoneId = it[ReservationsTable.zoneId],
                    status = ReservationStatus.valueOf(it[ReservationsTable.status]),
                    createdAt = it[ReservationsTable.createdAt],
                )
            }.singleOrNull()
    }
}
