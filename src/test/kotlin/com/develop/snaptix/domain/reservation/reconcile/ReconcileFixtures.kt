package com.develop.snaptix.domain.reservation.reconcile

import com.develop.snaptix.domain.auditlog.entity.AuditLogsTable
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/** 통합 테스트용 시드/조회 헬퍼. */
object ReconcileFixtures {
    data class SeededEvent(
        val eventId: Long,
        val publicId: String,
    )

    data class SeededZone(
        val zoneId: Long,
        val publicId: String,
    )

    fun cleanAll() = transaction {
        ReservationsTable.deleteAll()
        AuditLogsTable.deleteAll()
        ZonesTable.deleteAll()
        EventsTable.deleteAll()
        UsersTable.deleteAll()
    }

    fun insertUser(email: String = "user-${UUID.randomUUID()}@test.com"): Long = transaction {
        UsersTable.insert {
            it[UsersTable.email] = email
            it[UsersTable.password] = "encoded-password"
            it[UsersTable.role] = UserRole.USER.name
        }[UsersTable.id]
    }

    fun insertEvent(status: EventStatus = EventStatus.ON_SALE): SeededEvent = transaction {
        val publicId = UUID.randomUUID().toString()
        val now = Instant.parse("2027-12-25T10:00:00Z")
        val id =
            EventsTable.insert {
                it[EventsTable.publicId] = publicId
                it[EventsTable.name] = "SnapTix Concert"
                it[EventsTable.location] = "KSPO DOME"
                it[EventsTable.startTime] = now
                it[EventsTable.endTime] = now.plusSeconds(10_800)
                it[EventsTable.status] = status.name
            }[EventsTable.id]
        SeededEvent(eventId = id, publicId = publicId)
    }

    fun insertZone(
        eventId: Long,
        capacity: Int,
    ): SeededZone = transaction {
        val publicId = UUID.randomUUID().toString()
        val id =
            ZonesTable.insert {
                it[ZonesTable.publicId] = publicId
                it[ZonesTable.eventId] = eventId
                it[ZonesTable.name] = "A"
                it[ZonesTable.unitPrice] = 100_000
                it[ZonesTable.totalCapacity] = capacity
            }[ZonesTable.id]
        SeededZone(zoneId = id, publicId = publicId)
    }

    fun insertReservation(
        userId: Long,
        eventId: Long,
        zoneId: Long,
        status: ReservationStatus,
        createdAt: Instant,
        orderId: String = UUID.randomUUID().toString(),
    ): String = transaction {
        ReservationsTable.insert {
            it[ReservationsTable.orderId] = orderId
            it[ReservationsTable.userId] = userId
            it[ReservationsTable.eventId] = eventId
            it[ReservationsTable.zoneId] = zoneId
            it[ReservationsTable.amount] = 100_000
            it[ReservationsTable.status] = status.name
            it[ReservationsTable.createdAt] = createdAt
            it[ReservationsTable.updatedAt] = createdAt
        }
        orderId
    }

    fun findStatus(orderId: String): String = transaction {
        ReservationsTable
            .selectAll()
            .where { ReservationsTable.orderId eq orderId }
            .single()[ReservationsTable.status]
    }

    fun countAudit(actionType: String): Long = transaction {
        AuditLogsTable
            .selectAll()
            .where { AuditLogsTable.actionType eq actionType }
            .count()
    }
}
