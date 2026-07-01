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
        // 참고: 이 경로는 Security 필터(AuthenticationEntryPoint)에서 DispatcherServlet의
        // 핸들러 매핑(및 그 producible media type 기록)보다 먼저 끊기므로, Accept 헤더를
        // text/event-stream으로 제한해도 컨텐츠 협상 문제가 재현되지 않는다. 그래도 실제
        // SSE 클라이언트와 동일한 조건으로 검증하기 위해 명시적으로 붙여둔다.
        mockMvc
            .perform(
                get(ssePath(UUID.randomUUID().toString()))
                    .accept(MediaType.TEXT_EVENT_STREAM),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code))
    }

    @Test
    fun `다른 사용자의 주문 SSE 구독은 403을 반환한다`() {
        // 실제 SSE 클라이언트(xk6-sse, 브라우저 EventSource 등)는 Accept: text/event-stream을
        // 보낸다. 이전에는 이 헤더가 없어 테스트가 항상 "*/*"로 통과했고, 그 결과
        // OrderSseController가 produces=[TEXT_EVENT_STREAM_VALUE]로 매핑된 상태에서
        // BusinessException이 GlobalExceptionHandler(JSON 응답)까지 전파되면
        // HttpMediaTypeNotAcceptableException으로 크래시하는 버그를 이 테스트가 전혀
        // 잡아내지 못했다(부하 테스트에서만 발견됨). 회귀 방지를 위해 명시적으로 붙인다.
        val ownerId = insertUser()
        val otherUserId = insertUser()
        val orderId = insertReservation(userId = ownerId)

        mockMvc
            .perform(
                get(ssePath(orderId))
                    .cookie(accessTokenCookie(otherUserId))
                    .accept(MediaType.TEXT_EVENT_STREAM),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN_ACCESS.code))
    }

    @Test
    fun `존재하지 않는 주문 SSE 구독은 404를 반환한다`() {
        // 위와 동일한 이유로 Accept: text/event-stream을 명시한다(NOT_FOUND도 같은
        // BusinessException 경로를 타므로 동일한 컨텐츠 협상 문제에 노출돼 있었다).
        val userId = insertUser()

        mockMvc
            .perform(
                get(ssePath(UUID.randomUUID().toString()))
                    .cookie(accessTokenCookie(userId))
                    .accept(MediaType.TEXT_EVENT_STREAM),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `USER 권한이 아니면 SSE 구독을 요청할 수 없다`() {
        // 주의: 이 케이스(@PreAuthorize 실패 → AuthorizationDeniedException)는
        // OrderSseController의 컨트롤러 로컬 예외 처리(BusinessException 전용)로 커버되지
        // 않는다. AuthorizationDeniedException은 핸들러 메서드 진입 전 AOP에서 발생해
        // GlobalExceptionHandler.handleAuthorizationDenied()(JSON 응답)로 가는데, 이 요청도
        // 동일하게 produces=[TEXT_EVENT_STREAM_VALUE]로 매핑돼 있어 원리상 같은
        // HttpMediaTypeNotAcceptableException 크래시에 노출돼 있을 수 있다. Accept 헤더를
        // 붙였을 때 이 테스트가 실패하면 OrderSseController에 같은 우회 처리를
        // AuthorizationDeniedException까지 확장해야 한다는 뜻이다 — 로컬에서 먼저 실행해
        // 결과를 확인해달라.
        val adminId = insertUser(role = UserRole.ADMIN)
        val orderId = UUID.randomUUID().toString()

        mockMvc
            .perform(
                get(ssePath(orderId))
                    .cookie(accessTokenCookie(userId = adminId, role = UserRole.ADMIN))
                    .accept(MediaType.TEXT_EVENT_STREAM),
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
