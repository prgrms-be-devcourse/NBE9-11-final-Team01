package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

object OrderRepositoryFixtures {
    fun insertOrderTestUser(email: String = "order_test_${UUID.randomUUID()}@snaptix.com"): Long = transaction {
        UsersTable.insert {
            it[this.email] = email
            it[this.password] = "encrypted_password"
            it[this.role] = UserRole.USER.name
            it[this.createdAt] = Instant.now()
            it[this.updatedAt] = Instant.now()
        }[UsersTable.id] // insertAndGetId 대신 결과 Row에서 id 추출
    }

    fun insertOrderTestEvent(
        publicId: UUID = UUID.randomUUID(),
        status: EventStatus = EventStatus.ON_SALE, // 명세서 기준(ON_SALE 등)에 맞게 조정
    ): Long = transaction {
        EventsTable.insert {
            it[this.publicId] = publicId.toString()
            it[this.name] = "Test Event for Order"
            it[this.location] = "Test Location"
            it[this.startTime] = Instant.now().minusSeconds(3600)
            it[this.endTime] = Instant.now().plusSeconds(3600)
            it[this.status] = status.name
            it[this.createdAt] = Instant.now()
            it[this.updatedAt] = Instant.now()
        }[EventsTable.id]
    }

    fun insertOrderTestZone(
        eventId: Long,
        publicId: UUID = UUID.randomUUID(),
        capacity: Int = 100,
    ): Long = transaction {
        ZonesTable.insert {
            it[this.publicId] = publicId.toString()
            it[this.eventId] = eventId
            it[this.name] = "VIP Zone"
            it[this.unitPrice] = 150000
            it[this.totalCapacity] = capacity
            it[this.createdAt] = Instant.now()
            it[this.updatedAt] = Instant.now()
        }[ZonesTable.id]
    }

    fun insertOrderTestReservation(
        orderId: String,
        userId: Long,
        eventId: Long,
        zoneId: Long,
        status: ReservationStatus,
    ): Long = transaction {
        ReservationsTable.insert {
            it[this.orderId] = orderId
            it[this.userId] = userId
            it[this.eventId] = eventId
            it[this.zoneId] = zoneId
            it[this.amount] = 1
            it[this.status] = status.name
            it[this.createdAt] = Instant.now()
            it[this.updatedAt] = Instant.now()
        }[ReservationsTable.id]
    }
}
