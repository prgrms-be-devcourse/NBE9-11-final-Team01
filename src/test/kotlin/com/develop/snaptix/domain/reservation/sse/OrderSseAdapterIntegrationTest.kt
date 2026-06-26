package com.develop.snaptix.domain.reservation.sse

import com.develop.snaptix.domain.auditlog.entity.AuditLogsTable
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.ticket.entity.TicketsTable
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.redis.gateway.OwnershipRedisGateway
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest
class OrderSseAdapterIntegrationTest : OrderSseIntegrationSupport() {
    @Autowired
    private lateinit var orderSseAdapter: OrderSseAdapter

    @Autowired
    private lateinit var ownershipRedisGateway: OwnershipRedisGateway

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var redisKeys: RedisKeyFactory

    @BeforeEach
    fun setUp() {
        cleanDatabase()
        deleteRedisKeys("order:owner:*")
        deleteRedisKeys("ORDER_HOLD:*")
    }

    @Test
    fun `reservation 행 소유자가 현재 사용자면 OWNED를 반환한다`() {
        val ownerId = insertUser()
        val orderId = insertReservation(userId = ownerId)

        val result = orderSseAdapter.check(orderKey(orderId), ownerId.toString())

        assertThat(result).isEqualTo(OwnershipResult.OWNED)
    }

    @Test
    fun `reservation 행 소유자가 다른 사용자면 FORBIDDEN을 반환한다`() {
        val ownerId = insertUser()
        val otherUserId = insertUser()
        val orderId = insertReservation(userId = ownerId)

        val result = orderSseAdapter.check(orderKey(orderId), otherUserId.toString())

        assertThat(result).isEqualTo(OwnershipResult.FORBIDDEN)
    }

    @Test
    fun `reservation 행이 없고 Redis owner 키가 현재 사용자면 OWNED를 반환한다`() {
        val ownerId = insertUser()
        val orderId = UUID.randomUUID()
        ownershipRedisGateway.set(orderId, ownerId)

        val result = orderSseAdapter.check(orderKey(orderId.toString()), ownerId.toString())

        assertThat(result).isEqualTo(OwnershipResult.OWNED)
    }

    @Test
    fun `reservation 행이 없고 Redis owner 키가 다른 사용자면 FORBIDDEN을 반환한다`() {
        val ownerId = insertUser()
        val otherUserId = insertUser()
        val orderId = UUID.randomUUID()
        ownershipRedisGateway.set(orderId, ownerId)

        val result = orderSseAdapter.check(orderKey(orderId.toString()), otherUserId.toString())

        assertThat(result).isEqualTo(OwnershipResult.FORBIDDEN)
    }

    @Test
    fun `reservation 행과 Redis owner 키가 모두 없으면 NOT_FOUND를 반환한다`() {
        val userId = insertUser()
        val orderId = UUID.randomUUID().toString()

        val result = orderSseAdapter.check(orderKey(orderId), userId.toString())

        assertThat(result).isEqualTo(OwnershipResult.NOT_FOUND)
    }

    @Test
    fun `PENDING_PAYMENT가 홀드 윈도우 안이면 ORDER_HOLD 키가 없어도 READY_TO_PAY를 재구성한다`() {
        val ownerId = insertUser()
        val createdAt = Instant.now().minus(Duration.ofMinutes(3))
        val orderId =
            insertReservation(
                userId = ownerId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = createdAt,
            )

        assertThat(redisTemplate.hasKey(redisKeys.orderHold(UUID.fromString(orderId)))).isFalse()

        val event = orderSseAdapter.reconstruct(orderKey(orderId))

        assertThat(event).isNotNull()
        assertThat(event?.name).isEqualTo("READY_TO_PAY")
        assertThat(event?.terminal).isFalse()
        val data = event?.data as Map<*, *>
        assertThat(data["orderId"]).isEqualTo(orderId)
        assertThat(data["status"]).isEqualTo(ReservationStatus.PENDING_PAYMENT.name)
    }

    @Test
    fun `PENDING_PAYMENT가 홀드 윈도우를 넘으면 이벤트를 재구성하지 않는다`() {
        val ownerId = insertUser()
        val orderId =
            insertReservation(
                userId = ownerId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = Instant.now().minus(Duration.ofMinutes(6)),
            )

        val event = orderSseAdapter.reconstruct(orderKey(orderId))

        assertThat(event).isNull()
    }

    @Test
    fun `CONFIRMED 예약은 TICKET_ISSUED 터미널 이벤트로 재구성한다`() {
        val ownerId = insertUser()
        val orderId = insertReservation(userId = ownerId, status = ReservationStatus.CONFIRMED)

        val event = orderSseAdapter.reconstruct(orderKey(orderId))

        assertThat(event).isNotNull()
        assertThat(event?.name).isEqualTo("TICKET_ISSUED")
        assertThat(event?.terminal).isTrue()
    }

    @Test
    fun `CANCELLED 예약은 ORDER_FAILED 터미널 이벤트로 재구성한다`() {
        val ownerId = insertUser()
        val orderId = insertReservation(userId = ownerId, status = ReservationStatus.CANCELLED)

        val event = orderSseAdapter.reconstruct(orderKey(orderId))

        assertThat(event).isNotNull()
        assertThat(event?.name).isEqualTo("ORDER_FAILED")
        assertThat(event?.terminal).isTrue()
    }

    @Test
    fun `RELEASED 예약은 PAYMENT_TIMEOUT 터미널 이벤트로 재구성한다`() {
        val ownerId = insertUser()
        val orderId = insertReservation(userId = ownerId, status = ReservationStatus.RELEASED)

        val event = orderSseAdapter.reconstruct(orderKey(orderId))

        assertThat(event).isNotNull()
        assertThat(event?.name).isEqualTo("PAYMENT_TIMEOUT")
        assertThat(event?.terminal).isTrue()
    }

    private fun cleanDatabase() = transaction {
        TicketsTable.deleteAll()
        ReservationsTable.deleteAll()
        AuditLogsTable.deleteAll()
        ZonesTable.deleteAll()
        EventsTable.deleteAll()
        UsersTable.deleteAll()
    }

    private fun insertUser(email: String = "user-${UUID.randomUUID()}@test.com"): Long = transaction {
        UsersTable.insert {
            it[UsersTable.email] = email
            it[UsersTable.password] = "encoded-password"
            it[UsersTable.role] = UserRole.USER.name
        }[UsersTable.id]
    }

    private fun insertReservation(
        userId: Long,
        status: ReservationStatus = ReservationStatus.PENDING_PAYMENT,
        createdAt: Instant = Instant.now(),
    ): String {
        val eventId = insertEvent()
        val zoneId = insertZone(eventId = eventId, capacity = 100)
        return insertReservationRow(
            userId = userId,
            eventId = eventId,
            zoneId = zoneId,
            status = status,
            createdAt = createdAt,
        )
    }

    private fun insertEvent(status: EventStatus = EventStatus.ON_SALE): Long = transaction {
        val now = Instant.parse("2027-12-25T10:00:00Z")
        EventsTable.insert {
            it[EventsTable.publicId] = UUID.randomUUID().toString()
            it[EventsTable.name] = "SnapTix Concert"
            it[EventsTable.location] = "KSPO DOME"
            it[EventsTable.startTime] = now
            it[EventsTable.endTime] = now.plusSeconds(10_800)
            it[EventsTable.status] = status.name
        }[EventsTable.id]
    }

    private fun insertZone(
        eventId: Long,
        capacity: Int,
    ): Long = transaction {
        ZonesTable.insert {
            it[ZonesTable.publicId] = UUID.randomUUID().toString()
            it[ZonesTable.eventId] = eventId
            it[ZonesTable.name] = "A"
            it[ZonesTable.unitPrice] = 100_000
            it[ZonesTable.totalCapacity] = capacity
        }[ZonesTable.id]
    }

    private fun insertReservationRow(
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

    private fun orderKey(orderId: String): SseChannelKey = SseChannelKey("order", orderId)

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).forEach(redisTemplate::delete)
    }
}
