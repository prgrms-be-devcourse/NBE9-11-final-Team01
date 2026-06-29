package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderStatus
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse
import com.develop.snaptix.domain.order.api.port.OrderIngestPort
import com.develop.snaptix.domain.order.api.port.OrderQueryPort
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.security.config.SecurityConfig
import com.develop.snaptix.global.security.handler.CustomAccessDeniedHandler
import com.develop.snaptix.global.security.handler.CustomAuthenticationEntryPoint
import com.develop.snaptix.global.security.handler.SecurityErrorResponseWriter
import com.develop.snaptix.global.security.jwt.JwtProvider
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * OrderController 단위 테스트 (@WebMvcTest)
 *
 * - Spring 컨텍스트는 Web 레이어(컨트롤러·시큐리티·어드바이스)만 로드한다.
 * - 포트 구현체는 MockK 빈으로 교체하여 서비스·Redis·DB 의존을 완전 차단한다.
 */
@WebMvcTest(OrderController::class)
@Import(
    OrderControllerTest.MockPorts::class,
    SecurityConfig::class,
    CustomAuthenticationEntryPoint::class,
    CustomAccessDeniedHandler::class,
    SecurityErrorResponseWriter::class,
)
@DisplayName("OrderController 단위 테스트")
class OrderControllerTest {
    // ── MockK 포트 빈 등록 ───────────────────────────────────────────────────────
    @TestConfiguration
    class MockPorts {
        @Bean
        fun orderIngestPort(): OrderIngestPort = mockk(relaxed = true)

        @Bean
        fun orderQueryPort(): OrderQueryPort = mockk(relaxed = true)
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var orderIngestPort: OrderIngestPort

    @Autowired
    private lateinit var orderQueryPort: OrderQueryPort

    @MockitoBean
    private lateinit var jwtProvider: JwtProvider

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────
    private val testUserId = 12345L
    private val validEventId = UUID.randomUUID().toString()
    private val validZoneId = 30L

    /** Long principal 을 담은 인증 객체 — @AuthenticationPrincipal Long? 에 바인딩된다 */
    private val userAuth =
        UsernamePasswordAuthenticationToken(
            testUserId,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )

    private fun validRequestBody(
        eventId: String = validEventId,
        zoneId: Long = validZoneId,
    ) = """{"eventId": "$eventId", "zoneId": $zoneId}"""

    @BeforeEach
    fun resetMocks() {
        clearAllMocks()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // POST /api/v1/orders
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/orders — 주문 생성")
    inner class CreateOrder {
        @Test
        @DisplayName("인증 없이 요청 시 401 을 반환한다")
        fun `returns 401 when request has no authentication`() {
            mockMvc
                .post("/api/v1/orders") {
                    contentType = MediaType.APPLICATION_JSON
                    content = validRequestBody()
                }.andExpect {
                    status { isUnauthorized() }
                }
        }

        @Test
        @DisplayName("eventId 가 UUID 형식이 아니면 400 VALIDATION_FAILED 를 반환한다")
        fun `returns 400 when eventId is not UUID format`() {
            mockMvc
                .post("/api/v1/orders") {
                    with(authentication(userAuth))
                    contentType = MediaType.APPLICATION_JSON
                    content = validRequestBody(eventId = "not-a-uuid")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                }
        }

        @Test
        @DisplayName("eventId 가 빈 문자열이면 400 VALIDATION_FAILED 를 반환한다")
        fun `returns 400 when eventId is blank`() {
            mockMvc
                .post("/api/v1/orders") {
                    with(authentication(userAuth))
                    contentType = MediaType.APPLICATION_JSON
                    content = validRequestBody(eventId = "")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                }
        }

        @Test
        @DisplayName("eventId 가 누락되면 400 VALIDATION_FAILED 를 반환한다")
        fun `returns 400 when eventId is missing`() {
            mockMvc
                .post("/api/v1/orders") {
                    with(authentication(userAuth))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"zoneId": $validZoneId}"""
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                }
        }

        @Test
        @DisplayName("유효한 요청 시 202 와 orderId·sseUrl·statusUrl 을 반환한다")
        fun `returns 202 with orderId sseUrl statusUrl on valid request`() {
            val orderId = UUID.randomUUID().toString()
            every { orderIngestPort.ingest(testUserId, any(), any()) } returns
                OrderAcceptedResponse(
                    orderId = orderId,
                    sseUrl = "/api/v1/orders/sse/$orderId",
                    statusUrl = "/api/v1/orders/$orderId",
                    message = "주문 요청이 성공적으로 대기열에 접수되었습니다.",
                )

            mockMvc
                .post("/api/v1/orders") {
                    with(authentication(userAuth))
                    contentType = MediaType.APPLICATION_JSON
                    content = validRequestBody()
                }.andExpect {
                    status { isAccepted() }
                    jsonPath("$.orderId") { value(orderId) }
                    jsonPath("$.sseUrl") { value("/api/v1/orders/sse/$orderId") }
                    jsonPath("$.statusUrl") { value("/api/v1/orders/$orderId") }
                    jsonPath("$.message") { exists() }
                }
        }

        @Test
        @DisplayName("유효한 요청 시 ingest() 에 userId 와 request 가 전달된다")
        fun `delegates to ingest with userId and request on valid request`() {
            val orderId = UUID.randomUUID().toString()
            every { orderIngestPort.ingest(any(), any(), any()) } returns
                OrderAcceptedResponse(
                    orderId = orderId,
                    sseUrl = "/api/v1/orders/sse/$orderId",
                    statusUrl = "/api/v1/orders/$orderId",
                    message = "접수 완료",
                )

            mockMvc
                .post("/api/v1/orders") {
                    with(authentication(userAuth))
                    contentType = MediaType.APPLICATION_JSON
                    content = validRequestBody()
                }

            verify(exactly = 1) { orderIngestPort.ingest(testUserId, any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // GET /api/v1/orders/{orderId}
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/orders/{orderId} — 주문 상태 조회")
    inner class GetOrderStatus {
        private val testOrderId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

        @Test
        @DisplayName("인증 없이 요청 시 401 을 반환한다")
        fun `returns 401 when request has no authentication`() {
            mockMvc
                .get("/api/v1/orders/{orderId}", testOrderId)
                .andExpect {
                    status { isUnauthorized() }
                }
        }

        @Test
        @DisplayName("PENDING 상태 주문 조회 시 200 과 PENDING 상태를 반환한다")
        fun `returns 200 with PENDING status`() {
            every { orderQueryPort.getStatus(testUserId, testOrderId) } returns
                OrderStatusResponse(
                    orderId = testOrderId,
                    status = OrderStatus.PENDING,
                    message = "처리 대기 중입니다.",
                )

            mockMvc
                .get("/api/v1/orders/{orderId}", testOrderId) {
                    with(authentication(userAuth))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.orderId") { value(testOrderId) }
                    jsonPath("$.status") { value("PENDING") }
                }
        }

        @Test
        @DisplayName("PENDING 상태 응답에 Retry-After: 2 헤더가 포함된다")
        fun `includes Retry-After 2 header for PENDING status`() {
            every { orderQueryPort.getStatus(testUserId, testOrderId) } returns
                OrderStatusResponse(
                    orderId = testOrderId,
                    status = OrderStatus.PENDING,
                    message = "처리 대기 중입니다.",
                )

            mockMvc
                .get("/api/v1/orders/{orderId}", testOrderId) {
                    with(authentication(userAuth))
                }.andExpect {
                    status { isOk() }
                    header { string("Retry-After", "2") }
                }
        }

        @Test
        @DisplayName("READY_TO_PAY 상태 주문 조회 시 200 과 READY_TO_PAY 상태를 반환한다")
        fun `returns 200 with READY_TO_PAY status`() {
            every { orderQueryPort.getStatus(testUserId, testOrderId) } returns
                OrderStatusResponse(
                    orderId = testOrderId,
                    status = OrderStatus.READY_TO_PAY,
                )

            mockMvc
                .get("/api/v1/orders/{orderId}", testOrderId) {
                    with(authentication(userAuth))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("READY_TO_PAY") }
                }
        }

        @Test
        @DisplayName("PENDING 이외 상태 응답에 Retry-After 헤더가 포함되지 않는다")
        fun `does not include Retry-After header for non-PENDING status`() {
            every { orderQueryPort.getStatus(testUserId, testOrderId) } returns
                OrderStatusResponse(
                    orderId = testOrderId,
                    status = OrderStatus.READY_TO_PAY,
                )

            mockMvc
                .get("/api/v1/orders/{orderId}", testOrderId) {
                    with(authentication(userAuth))
                }.andExpect {
                    status { isOk() }
                    header { doesNotExist("Retry-After") }
                }
        }

        @Test
        @DisplayName("유효한 요청 시 getStatus() 에 userId 와 orderId 가 전달된다")
        fun `delegates to getStatus with userId and orderId`() {
            every { orderQueryPort.getStatus(any(), any()) } returns
                OrderStatusResponse(
                    orderId = testOrderId,
                    status = OrderStatus.PENDING,
                )

            mockMvc
                .get("/api/v1/orders/{orderId}", testOrderId) {
                    with(authentication(userAuth))
                }

            verify(exactly = 1) { orderQueryPort.getStatus(testUserId, testOrderId) }
        }
    }
}
