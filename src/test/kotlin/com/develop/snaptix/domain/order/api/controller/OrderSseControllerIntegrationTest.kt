package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.order.worker.OrderStreamConsumer
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.security.jwt.JwtProvider
import com.develop.snaptix.support.IntegrationTestSupport
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class OrderSseControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jwtProvider: JwtProvider,
    @Autowired private val sseConnectionManager: SseConnectionManager,
) : IntegrationTestSupport() {
    @MockitoBean
    private lateinit var orderStreamConsumer: OrderStreamConsumer

    private val openedOrderIds = mutableSetOf<String>()

    @AfterEach
    fun tearDownSseConnections() {
        openedOrderIds.forEach { orderId ->
            sseConnectionManager.close(orderKey(orderId))
        }
        openedOrderIds.clear()
    }

    @Test
    fun `본인 주문 SSE 구독은 text event stream 연결을 시작하고 READY_TO_PAY를 재구성한다`() {
        val ownerId = insertUser()
        val createdAt = Instant.now().minus(Duration.ofMinutes(1))
        val orderId =
            insertReservation(
                userId = ownerId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = createdAt,
            )

        val result =
            mockMvc
                .perform(
                    get(ssePath(orderId))
                        .cookie(accessTokenCookie(ownerId))
                        .accept(MediaType.TEXT_EVENT_STREAM),
                ).andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn()

        openedOrderIds += orderId
        val body = completeSseConnection(result, orderId)

        assertThat(body).contains("event:READY_TO_PAY")
        assertThat(body).contains(orderId)
        assertThat(body).contains(ReservationStatus.PENDING_PAYMENT.name)
        assertThat(body).contains("paymentDeadline")
    }

    @Test
    fun `JWT 없이 SSE 구독을 요청하면 401을 반환한다`() {
        mockMvc
            .perform(
                get(ssePath(UUID.randomUUID().toString())),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code))
    }

    @Test
    fun `다른 사용자의 주문 SSE 구독은 403을 반환한다`() {
        val ownerId = insertUser()
        val otherUserId = insertUser()
        val orderId = insertReservation(userId = ownerId)

        mockMvc
            .perform(
                get(ssePath(orderId))
                    .cookie(accessTokenCookie(otherUserId)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN_ACCESS.code))
    }

    @Test
    fun `존재하지 않는 주문 SSE 구독은 404를 반환한다`() {
        val userId = insertUser()

        mockMvc
            .perform(
                get(ssePath(UUID.randomUUID().toString()))
                    .cookie(accessTokenCookie(userId)),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `USER 권한이 아니면 SSE 구독을 요청할 수 없다`() {
        val adminId = insertUser(role = UserRole.ADMIN)
        val orderId = UUID.randomUUID().toString()

        mockMvc
            .perform(
                get(ssePath(orderId))
                    .cookie(accessTokenCookie(userId = adminId, role = UserRole.ADMIN)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code))
    }

    private fun completeSseConnection(
        result: MvcResult,
        orderId: String,
    ): String {
        waitUntilConnected()
        waitUntilResponseContains(result, "READY_TO_PAY")
        sseConnectionManager.close(orderKey(orderId))

        return result.response.contentAsString
    }

    private fun waitUntilConnected() {
        repeat(CONNECTION_WAIT_ATTEMPTS) {
            if (sseConnectionManager.activeConnections() > 0) {
                return
            }
            Thread.sleep(CONNECTION_WAIT_INTERVAL_MS)
        }
        error("SSE 연결이 활성화되지 않았습니다.")
    }

    private fun waitUntilResponseContains(
        result: MvcResult,
        expected: String,
    ) {
        repeat(RESPONSE_WAIT_ATTEMPTS) {
            if (result.response.contentAsString.contains(expected)) {
                return
            }
            Thread.sleep(RESPONSE_WAIT_INTERVAL_MS)
        }
        error("SSE 응답에 '$expected' 이벤트가 기록되지 않았습니다.")
    }

    private fun insertUser(
        email: String = "user-${UUID.randomUUID()}@test.com",
        role: UserRole = UserRole.USER,
    ): Long = transaction {
        UsersTable.insert {
            it[UsersTable.email] = email
            it[UsersTable.password] = "encoded-password"
            it[UsersTable.role] = role.name
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
            it[EventsTable.endTime] = now.plusSeconds(EVENT_DURATION_SECONDS)
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

    private fun accessTokenCookie(
        userId: Long,
        role: UserRole = UserRole.USER,
    ): Cookie {
        val token = jwtProvider.createAccessToken(userId = userId, role = role)
        return Cookie("accessToken", token)
    }

    private fun ssePath(orderId: String): String = "/api/v1/orders/sse/$orderId"

    private fun orderKey(orderId: String): SseChannelKey = SseChannelKey("order", orderId)

    companion object {
        private const val EVENT_DURATION_SECONDS = 10_800L
        private const val CONNECTION_WAIT_ATTEMPTS = 20
        private const val CONNECTION_WAIT_INTERVAL_MS = 50L
        private const val RESPONSE_WAIT_ATTEMPTS = 20
        private const val RESPONSE_WAIT_INTERVAL_MS = 50L
    }
}
