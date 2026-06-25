package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.global.exception.ErrorCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    companion object {
        @Container
        @JvmStatic
        val mysql =
            MySQLContainer("mysql:9.7").apply {
                withDatabaseName("snaptix")
                withUsername("snaptix")
                withPassword("snaptix1234")
            }

        @Container
        @JvmStatic
        val redis =
            GenericContainer("redis:8.8.0").apply {
                withExposedPorts(6379)
            }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-event-flow-256-bit" }
        }
    }

    private val validEventId = UUID.randomUUID().toString()
    private val validZoneId = UUID.randomUUID().toString()

    @Test
    @DisplayName("인증 쿠키(userId) 없이 주문 생성 요청 시 401 예외가 발생한다")
    fun `createOrder without auth throws 401`() {
        mockMvc
            .post("/api/v1/orders") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "eventId": "$validEventId",
                      "zoneId": "$validZoneId"
                    }
                    """.trimIndent()
            }.andExpect {
                status { isUnauthorized() }
                // Security 필터 또는 Controller 널 체크 로직에 의해 401 응답
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
            }
    }

    @Test
    @DisplayName("인증된 유저는 주문 생성을 성공적으로 요청할 수 있다")
    fun `createOrder with authenticated user succeeds`() {
        // given: 인증된 userId 생성 (Controller에서 @AuthenticationPrincipal Long? 타입으로 받기 위함)
        val testUserId = 12345L
        val auth =
            UsernamePasswordAuthenticationToken(
                testUserId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        // when & then: 인증 객체를 주입하여 요청하면 더미 어댑터의 202 응답을 받는다
        mockMvc
            .post("/api/v1/orders") {
                with(authentication(auth)) // MockMvc DSL의 인증 주입
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "eventId": "$validEventId",
                      "zoneId": "$validZoneId"
                    }
                    """.trimIndent()
            }.andExpect {
                status { isAccepted() }
                jsonPath("$.orderId") { exists() }
                jsonPath("$.message") { value("[DUMMY] 주문 요청이 성공적으로 대기열에 접수되었습니다.") }
            }
    }

    @Test
    @DisplayName("인증된 유저는 주문 상태 조회를 성공적으로 요청할 수 있다")
    fun `getOrderStatus with authenticated user succeeds`() {
        val testUserId = 67890L
        val auth =
            UsernamePasswordAuthenticationToken(
                testUserId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        val testOrderId = "test-dummy-order-id"

        mockMvc
            .get("/api/v1/orders/{orderId}", testOrderId) {
                with(authentication(auth))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(testOrderId) }
                jsonPath("$.status") { value("PENDING") }
            }
    }
}
