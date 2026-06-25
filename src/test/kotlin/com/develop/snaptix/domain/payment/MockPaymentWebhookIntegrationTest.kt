package com.develop.snaptix.domain.payment

import com.develop.snaptix.domain.payment.dto.MockPaymentStatus
import com.develop.snaptix.domain.payment.service.MockPaymentWebhookSignatureVerifier
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
import java.time.Instant
import java.util.UUID

private const val WEBHOOK_PATH = "/api/v1/payments/mock/webhook"

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MockPaymentWebhookIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val signatureVerifier: MockPaymentWebhookSignatureVerifier,
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
            registry.add("payment.mock.webhook.secret") { "integration-test-mock-payment-webhook-secret" }
        }
    }

    @BeforeEach
    fun setUp() {
        ReconcileFixtures.cleanAll()
        deleteRedisKeys("webhook:processed:*")
    }

    @Test
    fun `결제 성공 Webhook은 예약을 확정하고 paidAt을 기록한다`() {
        val orderId = insertReservation(status = ReservationStatus.PENDING_PAYMENT)
        val body = webhookBody(orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.processed") { value(true) }
            }

        val reservation = findReservation(orderId)
        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED.name)
        assertThat(reservation.paidAt).isNotNull()
        assertThat(redisTemplate.hasKey(webhookProcessedKey(orderId))).isTrue()
    }

    @Test
    fun `결제 실패 Webhook은 예약을 취소하고 paidAt을 기록하지 않는다`() {
        val orderId = insertReservation(status = ReservationStatus.PENDING_PAYMENT)
        val body = webhookBody(orderId, MockPaymentStatus.FAIL, failReason = "CARD_DECLINED")

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.processed") { value(true) }
            }

        val reservation = findReservation(orderId)
        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELLED.name)
        assertThat(reservation.paidAt).isNull()
        assertThat(redisTemplate.hasKey(webhookProcessedKey(orderId))).isTrue()
    }

    @Test
    fun `동일 Webhook을 중복 수신하면 두 번째 요청은 스킵한다`() {
        val orderId = insertReservation(status = ReservationStatus.PENDING_PAYMENT)
        val body = webhookBody(orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.processed") { value(true) }
            }

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.processed") { value(false) }
            }

        assertThat(findReservation(orderId).status).isEqualTo(ReservationStatus.CONFIRMED.name)
    }

    @Test
    fun `이미 처리된 Webhook 키가 있으면 예약 상태를 변경하지 않고 스킵한다`() {
        val orderId = insertReservation(status = ReservationStatus.PENDING_PAYMENT)
        redisTemplate.opsForValue().set(webhookProcessedKey(orderId), "1")
        val body = webhookBody(orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.processed") { value(false) }
            }

        val reservation = findReservation(orderId)
        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING_PAYMENT.name)
        assertThat(reservation.paidAt).isNull()
    }

    @Test
    fun `이미 확정된 예약의 성공 Webhook은 상태를 변경하지 않고 스킵한다`() {
        val orderId = insertReservation(status = ReservationStatus.CONFIRMED)
        val body = webhookBody(orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.processed") { value(false) }
            }

        val reservation = findReservation(orderId)
        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED.name)
        assertThat(reservation.paidAt).isNull()
        assertThat(redisTemplate.hasKey(webhookProcessedKey(orderId))).isFalse()
    }

    @Test
    fun `이미 취소된 예약의 실패 Webhook은 상태를 변경하지 않고 스킵한다`() {
        val orderId = insertReservation(status = ReservationStatus.CANCELLED)
        val body = webhookBody(orderId, MockPaymentStatus.FAIL, failReason = "CARD_DECLINED")

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(orderId) }
                jsonPath("$.processed") { value(false) }
            }

        val reservation = findReservation(orderId)
        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELLED.name)
        assertThat(reservation.paidAt).isNull()
        assertThat(redisTemplate.hasKey(webhookProcessedKey(orderId))).isFalse()
    }

    @Test
    fun `HMAC 서명이 없으면 Webhook을 처리하지 않는다`() {
        val body = webhookBody(UUID.randomUUID().toString(), MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID.code) }
            }
    }

    @Test
    fun `HMAC 서명이 일치하지 않으면 Webhook을 처리하지 않는다`() {
        val body = webhookBody(UUID.randomUUID().toString(), MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, "sha256=invalid")
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID.code) }
            }
    }

    @Test
    fun `서명 후 본문이 변조되면 Webhook을 처리하지 않는다`() {
        val originalBody = webhookBody(UUID.randomUUID().toString(), MockPaymentStatus.SUCCESS)
        val tamperedBody = webhookBody(UUID.randomUUID().toString(), MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = tamperedBody
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(originalBody))
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID.code) }
            }
    }

    @Test
    fun `잘못된 JSON 본문이면 400을 반환한다`() {
        val body = "{"

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }
    }

    @Test
    fun `orderId가 UUID 형식이 아니면 필드 에러를 반환한다`() {
        val body = rawWebhookBody(orderId = "invalid-order-id", paymentStatus = "SUCCESS")

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors[0].field") { value("orderId") }
            }
    }

    @Test
    fun `paymentStatus가 없으면 400을 반환한다`() {
        val body = objectMapper.writeValueAsString(mapOf("orderId" to UUID.randomUUID().toString()))

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }
    }

    @Test
    fun `paymentStatus가 허용되지 않는 값이면 400을 반환한다`() {
        val body = rawWebhookBody(orderId = UUID.randomUUID().toString(), paymentStatus = "UNKNOWN")

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }
    }

    @Test
    fun `failReason 길이가 초과되면 필드 에러를 반환한다`() {
        val body =
            webhookBody(
                orderId = UUID.randomUUID().toString(),
                paymentStatus = MockPaymentStatus.FAIL,
                failReason = "A".repeat(101),
            )

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors[0].field") { value("failReason") }
            }
    }

    @Test
    fun `존재하지 않는 주문이면 404를 반환하고 Webhook 멱등 키를 생성하지 않는다`() {
        val orderId = UUID.randomUUID().toString()
        val body = webhookBody(orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.ORDER_NOT_FOUND.code) }
            }

        assertThat(redisTemplate.hasKey(webhookProcessedKey(orderId))).isFalse()
    }

    private fun insertReservation(status: ReservationStatus): String {
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent()
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        return ReconcileFixtures.insertReservation(
            userId = userId,
            eventId = event.eventId,
            zoneId = zone.zoneId,
            status = status,
            createdAt = Instant.now(),
        )
    }

    private fun findReservation(orderId: String): ReservationSnapshot = transaction {
        ReservationsTable
            .selectAll()
            .where { ReservationsTable.orderId eq orderId }
            .map {
                ReservationSnapshot(
                    status = it[ReservationsTable.status],
                    paidAt = it[ReservationsTable.paidAt],
                )
            }.single()
    }

    private fun webhookBody(
        orderId: String,
        paymentStatus: MockPaymentStatus,
        failReason: String? = null,
    ): String = objectMapper
        .writeValueAsString(
            mapOf(
                "orderId" to orderId,
                "paymentStatus" to paymentStatus.name,
                "failReason" to failReason,
            ),
        )

    private fun rawWebhookBody(
        orderId: String,
        paymentStatus: String,
    ): String = objectMapper
        .writeValueAsString(
            mapOf(
                "orderId" to orderId,
                "paymentStatus" to paymentStatus,
            ),
        )

    private fun signature(body: String): String = signatureVerifier.sign(body)

    private fun webhookProcessedKey(orderId: String): String = "webhook:processed:$orderId"

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).forEach(redisTemplate::delete)
    }

    private data class ReservationSnapshot(
        val status: String,
        val paidAt: Instant?,
    )
}
