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
        }[UsersTable.id]
    }

    fun insertOrderTestEvent(
        publicId: UUID = UUID.randomUUID(),
        status: EventStatus = EventStatus.ON_SALE,
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

    /**
     * @param createdAt 기본값 [Instant.now()]. 만료 시나리오 테스트 시 과거 시각을 지정한다.
     *   예: `Instant.now().minusSeconds(600)` → 10분 전 생성(5분 홀드 타임아웃 초과)
     */
    fun insertOrderTestReservation(
        orderId: String,
        userId: Long,
        eventId: Long,
        zoneId: Long,
        status: ReservationStatus,
        createdAt: Instant = Instant.now(),
    ): Long = transaction {
        ReservationsTable.insert {
            it[this.orderId] = orderId
            it[this.userId] = userId
            it[this.eventId] = eventId
            it[this.zoneId] = zoneId
            it[this.amount] = 1
            it[this.status] = status.name
            it[this.createdAt] = createdAt
            it[this.updatedAt] = Instant.now()
        }[ReservationsTable.id]
    }
}
