package com.develop.snaptix.domain.payment

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.security.jwt.JwtProvider
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val APPROVE_PATH = "/api/v1/payments/mock/approve"

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MockPaymentApproveIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jwtProvider: JwtProvider,
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
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-payment-flow-256-bit" }
        }
    }

    @BeforeEach
    fun setUp() {
        ReconcileFixtures.cleanAll()
        deleteRedisKeys("ORDER_HOLD:*")
        deleteRedisKeys("payment:approve:*")
    }

    @Test
    fun `PENDING_PAYMENT 주문은 결제 승인 요청을 전송할 수 있다`() {
        val userId = ReconcileFixtures.insertUser()
        val orderId = insertReservation(userId = userId, status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(orderId)

        mockMvc
            .post(APPROVE_PATH) {
                cookie(accessTokenCookie(userId))
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(orderId)
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.message") { value("결제 요청이 전송되었습니다. 결과는 SSE로 전달됩니다.") }
            }

        assertThat(redisTemplate.hasKey("payment:approve:$orderId")).isTrue()
    }

    @Test
    fun `인증되지 않은 사용자는 결제 승인 요청을 할 수 없다`() {
        mockMvc
            .post(APPROVE_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(UUID.randomUUID().toString())
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
            }
    }

    @Test
    fun `USER 권한이 아니면 결제 승인 요청을 할 수 없다`() {
        val ownerId = ReconcileFixtures.insertUser()
        val orderId = insertReservation(userId = ownerId, status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(orderId)

        mockMvc
            .post(APPROVE_PATH) {
                cookie(accessTokenCookie(userId = ownerId, role = UserRole.ADMIN))
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(orderId)
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ACCESS_DENIED.code) }
            }
    }

    @Test
    fun `다른 사용자의 주문은 결제 승인 요청을 할 수 없다`() {
        val ownerId = ReconcileFixtures.insertUser()
        val requesterId = ReconcileFixtures.insertUser()
        val orderId = insertReservation(userId = ownerId, status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(orderId)

        mockMvc
            .post(APPROVE_PATH) {
                cookie(accessTokenCookie(requesterId))
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(orderId)
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ORDER_ACCESS_DENIED.code) }
            }
    }

    @Test
    fun `존재하지 않는 주문은 결제 승인 요청을 할 수 없다`() {
        val userId = ReconcileFixtures.insertUser()

        mockMvc
            .post(APPROVE_PATH) {
                cookie(accessTokenCookie(userId))
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(UUID.randomUUID().toString())
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.ORDER_NOT_FOUND.code) }
            }
    }

    @Test
    fun `PENDING_PAYMENT가 아닌 주문은 결제 승인 요청을 할 수 없다`() {
        val userId = ReconcileFixtures.insertUser()
        val orderId = insertReservation(userId = userId, status = ReservationStatus.CONFIRMED)

        mockMvc
            .post(APPROVE_PATH) {
                cookie(accessTokenCookie(userId))
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(orderId)
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value(ErrorCode.ORDER_NOT_PAYABLE.code) }
            }
    }

    @Test
    fun `결제 대기 시간이 만료된 주문은 결제 승인 요청을 할 수 없다`() {
        val userId = ReconcileFixtures.insertUser()
        val orderId =
            insertReservation(
                userId = userId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = Instant.now().minus(Duration.ofMinutes(10)),
            )

        mockMvc
            .post(APPROVE_PATH) {
                cookie(accessTokenCookie(userId))
                contentType = MediaType.APPLICATION_JSON
                content = approveBody(orderId)
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value(ErrorCode.ORDER_HOLD_EXPIRED.code) }
            }
    }

    @Test
    fun `중복 결제 승인 요청도 멱등하게 200을 반환한다`() {
        val userId = ReconcileFixtures.insertUser()
        val orderId = insertReservation(userId = userId, status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(orderId)

        repeat(2) {
            mockMvc
                .post(APPROVE_PATH) {
                    cookie(accessTokenCookie(userId))
                    contentType = MediaType.APPLICATION_JSON
                    content = approveBody(orderId)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.orderId") { value(orderId) }
                }
        }
    }

    private fun insertReservation(
        userId: Long,
        status: ReservationStatus,
        createdAt: Instant = Instant.now(),
    ): String {
        val event = ReconcileFixtures.insertEvent(EventStatus.ON_SALE)
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        return ReconcileFixtures.insertReservation(
            userId = userId,
            eventId = event.eventId,
            zoneId = zone.zoneId,
            status = status,
            createdAt = createdAt,
        )
    }

    private fun accessTokenCookie(
        userId: Long,
        role: UserRole = UserRole.USER,
    ): Cookie {
        val token = jwtProvider.createAccessToken(userId = userId, role = role)
        return Cookie("accessToken", token)
    }

    private fun approveBody(orderId: String): String = objectMapper.writeValueAsString(mapOf("orderId" to orderId))

    private fun createOrderHold(orderId: String) {
        redisTemplate.opsForValue().set("ORDER_HOLD:$orderId", "1", Duration.ofMinutes(5))
    }

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).forEach(redisTemplate::delete)
    }
}
