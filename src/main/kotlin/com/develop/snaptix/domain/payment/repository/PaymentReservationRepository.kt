package com.develop.snaptix.domain.payment.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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

data class PaymentWebhookProcessResult(
    val processed: Boolean,
    val reservation: PaymentReservation,
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

    fun confirmIfPending(orderId: String): PaymentWebhookProcessResult? = transaction {
        val reservation = findByOrderIdInTransaction(orderId) ?: return@transaction null
        val now = Instant.now()
        val updated =
            ReservationsTable.update(
                {
                    (ReservationsTable.id eq reservation.id) and
                        (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name)
                },
            ) {
                it[status] = ReservationStatus.CONFIRMED.name
                it[paidAt] = now
                it[updatedAt] = now
            }

        if (updated == 0) {
            return@transaction PaymentWebhookProcessResult(
                processed = false,
                reservation = findByOrderIdInTransaction(orderId) ?: reservation,
            )
        }

        PaymentWebhookProcessResult(
            processed = true,
            reservation = reservation.copy(status = ReservationStatus.CONFIRMED),
        )
    }

    fun cancelIfPending(orderId: String): PaymentWebhookProcessResult? = transaction {
        val reservation = findByOrderIdInTransaction(orderId) ?: return@transaction null
        val now = Instant.now()
        val updated =
            ReservationsTable.update(
                {
                    (ReservationsTable.id eq reservation.id) and
                        (ReservationsTable.status eq ReservationStatus.PENDING_PAYMENT.name)
                },
            ) {
                it[status] = ReservationStatus.CANCELLED.name
                it[updatedAt] = now
            }

        PaymentWebhookProcessResult(
            processed = updated == 1,
            reservation =
                if (updated == 1) {
                    reservation.copy(status = ReservationStatus.CANCELLED)
                } else {
                    findByOrderIdInTransaction(orderId) ?: reservation
                },
        )
    }

    private fun findByOrderIdInTransaction(orderId: String): PaymentReservation? = ReservationsTable
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
