package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

data class ReservationVerifyView(
    val id: Long,
    val eventId: Long,
)

@Repository
class ReservationRepository : ReservationQuery {
    override fun findByOrderId(orderId: String): ReservationView? =
        transaction {
            ReservationsTable
                .selectAll()
                .where { ReservationsTable.orderId eq orderId }
                .limit(1)
                .map { row ->
                    ReservationView(
                        userId = row[ReservationsTable.userId],
                        status = ReservationStatus.valueOf(row[ReservationsTable.status]),
                        createdAt = row[ReservationsTable.createdAt],
                    )
                }.singleOrNull()
        }

    fun findVerifyTarget(
        reservationId: Long,
    ): ReservationVerifyView? =
        transaction {
            ReservationsTable
                .selectAll()
                .where { ReservationsTable.id eq reservationId }
                .limit(1)
                .map {
                    ReservationVerifyView(
                        id = it[ReservationsTable.id],
                        eventId = it[ReservationsTable.eventId],
                    )
                }.singleOrNull()
        }
}
