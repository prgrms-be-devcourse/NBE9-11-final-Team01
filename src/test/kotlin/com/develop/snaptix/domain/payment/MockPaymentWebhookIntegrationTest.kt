package com.develop.snaptix.domain.payment

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.payment.dto.MockPaymentStatus
import com.develop.snaptix.domain.payment.service.MockPaymentWebhookSignatureVerifier
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.domain.ticket.entity.TicketsTable
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
import java.time.Duration
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
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-payment-webhook-flow-256-bit" }
            registry.add("payment.mock.webhook.secret") { "integration-test-mock-payment-webhook-secret" }
        }
    }

    @BeforeEach
    fun setUp() {
        ReconcileFixtures.cleanAll()
        deleteRedisKeys("ORDER_HOLD:*")
        deleteRedisKeys("webhook:processed:*")
        deleteRedisKeys("ZONE:*:stock")
        deleteRedisKeys("ZONE:*:claimed")
    }

    @Test
    fun `결제 성공 Webhook은 예약을 확정하고 티켓을 발급한다`() {
        val seed = insertPaymentReservation(status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(seed.orderId)
        seedClaimedStock(seed, stock = 0)
        val body = webhookBody(seed.orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(seed.orderId) }
                jsonPath("$.processed") { value(true) }
                jsonPath("$.message") { value("결제 결과가 처리되었습니다.") }
            }

        assertThat(findReservationStatus(seed.orderId)).isEqualTo(ReservationStatus.CONFIRMED.name)
        assertThat(countTickets(seed.reservationId)).isEqualTo(1)
        assertThat(redisTemplate.opsForValue().get("ZONE:${seed.zoneId}:stock")).isEqualTo("0")
        assertThat(redisTemplate.opsForSet().isMember("ZONE:${seed.zoneId}:claimed", seed.orderId)).isFalse()
        assertThat(redisTemplate.hasKey("ORDER_HOLD:${seed.orderId}")).isFalse()
        assertThat(redisTemplate.hasKey("webhook:processed:${seed.orderId}")).isTrue()
    }

    @Test
    fun `결제 실패 Webhook은 예약을 취소하고 재고를 보상한다`() {
        val seed = insertPaymentReservation(status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(seed.orderId)
        seedClaimedStock(seed, stock = 0)
        val body = webhookBody(seed.orderId, MockPaymentStatus.FAIL, failReason = "CARD_DECLINED")

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.orderId") { value(seed.orderId) }
                jsonPath("$.processed") { value(true) }
            }

        assertThat(findReservationStatus(seed.orderId)).isEqualTo(ReservationStatus.CANCELLED.name)
        assertThat(countTickets(seed.reservationId)).isZero()
        assertThat(redisTemplate.opsForValue().get("ZONE:${seed.zoneId}:stock")).isEqualTo("1")
        assertThat(redisTemplate.opsForSet().isMember("ZONE:${seed.zoneId}:claimed", seed.orderId)).isFalse()
        assertThat(redisTemplate.hasKey("ORDER_HOLD:${seed.orderId}")).isFalse()
        assertThat(redisTemplate.hasKey("webhook:processed:${seed.orderId}")).isTrue()
    }

    @Test
    fun `이미 처리된 주문의 Webhook은 processed false로 응답한다`() {
        val seed = insertPaymentReservation(status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(seed.orderId)
        seedClaimedStock(seed, stock = 0)
        val body = webhookBody(seed.orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.processed") { value(true) }
            }

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.processed") { value(false) }
                jsonPath("$.message") { value("이미 처리된 결제 결과입니다.") }
            }

        assertThat(findReservationStatus(seed.orderId)).isEqualTo(ReservationStatus.CONFIRMED.name)
        assertThat(countTickets(seed.reservationId)).isEqualTo(1)
    }

    @Test
    fun `결제 실패 Webhook 재시도는 보상되지 않은 재고를 다시 보상한다`() {
        val seed = insertPaymentReservation(status = ReservationStatus.CANCELLED)
        createOrderHold(seed.orderId)
        seedClaimedStock(seed, stock = 0)
        val body = webhookBody(seed.orderId, MockPaymentStatus.FAIL, failReason = "CARD_DECLINED")

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.processed") { value(false) }
            }

        assertThat(findReservationStatus(seed.orderId)).isEqualTo(ReservationStatus.CANCELLED.name)
        assertThat(redisTemplate.opsForValue().get("ZONE:${seed.zoneId}:stock")).isEqualTo("1")
        assertThat(redisTemplate.opsForSet().isMember("ZONE:${seed.zoneId}:claimed", seed.orderId)).isFalse()
        assertThat(redisTemplate.hasKey("ORDER_HOLD:${seed.orderId}")).isFalse()
    }

    @Test
    fun `존재하지 않는 주문의 Webhook은 404를 반환한다`() {
        val body = webhookBody(UUID.randomUUID().toString(), MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.ORDER_NOT_FOUND.code) }
            }
    }

    @Test
    fun `Webhook은 인증 쿠키 없이 호출할 수 있다`() {
        val seed = insertPaymentReservation(status = ReservationStatus.PENDING_PAYMENT)
        createOrderHold(seed.orderId)
        seedClaimedStock(seed, stock = 0)
        val body = webhookBody(seed.orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, signature(body))
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.processed") { value(true) }
            }
    }

    @Test
    fun `Webhook 서명이 유효하지 않으면 401을 반환한다`() {
        val seed = insertPaymentReservation(status = ReservationStatus.PENDING_PAYMENT)
        val body = webhookBody(seed.orderId, MockPaymentStatus.SUCCESS)

        mockMvc
            .post(WEBHOOK_PATH) {
                contentType = MediaType.APPLICATION_JSON
                header(MockPaymentWebhookSignatureVerifier.HEADER_NAME, "sha256=invalid")
                content = body
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID.code) }
            }

        assertThat(findReservationStatus(seed.orderId)).isEqualTo(ReservationStatus.PENDING_PAYMENT.name)
    }

    private fun insertPaymentReservation(status: ReservationStatus): SeededPayment {
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent(EventStatus.ON_SALE)
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        val orderId =
            ReconcileFixtures.insertReservation(
                userId = userId,
                eventId = event.eventId,
                zoneId = zone.zoneId,
                status = status,
                createdAt = Instant.now(),
            )
        val reservationId = findReservationId(orderId)
        return SeededPayment(
            orderId = orderId,
            reservationId = reservationId,
            zoneId = zone.zoneId,
        )
    }

    private fun createOrderHold(orderId: String) {
        redisTemplate.opsForValue().set("ORDER_HOLD:$orderId", "1", Duration.ofMinutes(5))
    }

    private fun seedClaimedStock(
        seed: SeededPayment,
        stock: Int,
    ) {
        redisTemplate.opsForValue().set("ZONE:${seed.zoneId}:stock", stock.toString())
        redisTemplate.opsForSet().add("ZONE:${seed.zoneId}:claimed", seed.orderId)
    }

    private fun webhookBody(
        orderId: String,
        paymentStatus: MockPaymentStatus,
        failReason: String? = null,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "orderId" to orderId,
            "paymentStatus" to paymentStatus.name,
            "failReason" to failReason,
        ),
    )

    private fun signature(body: String): String = signatureVerifier.sign(body)

    private fun findReservationId(orderId: String): Long = transaction {
        ReservationsTable
            .selectAll()
            .where { ReservationsTable.orderId eq orderId }
            .single()[ReservationsTable.id]
    }

    private fun findReservationStatus(orderId: String): String = transaction {
        ReservationsTable
            .selectAll()
            .where { ReservationsTable.orderId eq orderId }
            .single()[ReservationsTable.status]
    }

    private fun countTickets(reservationId: Long): Long = transaction {
        TicketsTable
            .selectAll()
            .where { TicketsTable.reservationId eq reservationId }
            .count()
    }

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).forEach(redisTemplate::delete)
    }

    private data class SeededPayment(
        val orderId: String,
        val reservationId: Long,
        val zoneId: Long,
    )
}
