package com.develop.snaptix.domain.ticket.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * [TicketRepositoryTest] 전용 통합 테스트 픽스처.
 *
 * [com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures] 는 Story-13
 * (드리프트 조정) 전용이므로 재사용하지 않는다. 픽스처 책임을 도메인별로 분리한다.
 */
object TicketFixtures {
    /**
     * tickets 행 삽입에 필요한 FK(`reservation_id`)를 갖춘 예약 1건을 삽입하고
     * 해당 예약의 내부 PK(`reservationId`)를 반환한다.
     *
     * 삽입 순서: users → events → zones → reservations
     *
     * @param status 예약 상태 (기본값 [ReservationStatus.CONFIRMED])
     * @return `reservations.id` (PK) — `TicketsTable.reservationId` FK로 사용
     */
    fun insertReservationAndGetId(status: ReservationStatus = ReservationStatus.CONFIRMED): Long = transaction {
        val userId =
            UsersTable.insert {
                it[UsersTable.email] = "ticket-test-${UUID.randomUUID()}@snaptix.com"
                it[UsersTable.password] = "encoded-password"
                it[UsersTable.role] = UserRole.USER.name
            }[UsersTable.id]

        val now = Instant.now()
        val eventId =
            EventsTable.insert {
                it[EventsTable.publicId] = UUID.randomUUID().toString()
                it[EventsTable.name] = "Ticket Test Event"
                it[EventsTable.location] = "Test Venue"
                it[EventsTable.startTime] = now.plusSeconds(3_600)
                it[EventsTable.endTime] = now.plusSeconds(7_200)
                it[EventsTable.status] = EventStatus.ON_SALE.name
            }[EventsTable.id]

        val zoneId =
            ZonesTable.insert {
                it[ZonesTable.publicId] = UUID.randomUUID().toString()
                it[ZonesTable.eventId] = eventId
                it[ZonesTable.name] = "A"
                it[ZonesTable.unitPrice] = 100_000
                it[ZonesTable.totalCapacity] = 10
            }[ZonesTable.id]

        val orderId = UUID.randomUUID().toString()
        ReservationsTable.insert {
            it[ReservationsTable.orderId] = orderId
            it[ReservationsTable.userId] = userId
            it[ReservationsTable.eventId] = eventId
            it[ReservationsTable.zoneId] = zoneId
            it[ReservationsTable.amount] = 1
            it[ReservationsTable.status] = status.name
            it[ReservationsTable.createdAt] = now
            it[ReservationsTable.updatedAt] = now
        }

        // reservations.id(PK) 반환 — orderId로 재조회
        ReservationsTable
            .selectAll()
            .where { ReservationsTable.orderId eq orderId }
            .single()[ReservationsTable.id]
    }
}
