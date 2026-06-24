package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.config.RedisTtlProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
class GuardsGatewayTest {
    @Autowired
    private lateinit var rateLimit: RateLimitRedisGateway

    @Autowired
    private lateinit var webhookGuard: WebhookGuardRedisGateway

    @Autowired
    private lateinit var paymentGuard: PaymentApproveGuardGateway

    @Autowired
    private lateinit var ttl: RedisTtlProperties

    @Test
    fun `한도 내 요청은 허용되고 retryAfter는 null`() {
        val result = rateLimit.hit(uniqueIp(), PER_SECOND, PER_MINUTE)

        assertThat(result.allowed).isTrue()
        assertThat(result.retryAfter).isNull()
    }

    @Test
    fun `초당 한도 초과 시 차단하고 retryAfter는 초 윈도우`() {
        val ip = uniqueIp()
        repeat(PER_SECOND) { rateLimit.hit(ip, PER_SECOND, HIGH_LIMIT) }

        val blocked = rateLimit.hit(ip, PER_SECOND, HIGH_LIMIT)

        assertThat(blocked.allowed).isFalse()
        assertThat(blocked.retryAfter).isEqualTo(ttl.rateLimitSecond)
    }

    @Test
    fun `분당 한도 초과 시 차단하고 retryAfter는 분 윈도우`() {
        val ip = uniqueIp()
        repeat(LOW_MINUTE) { rateLimit.hit(ip, HIGH_LIMIT, LOW_MINUTE) }

        val blocked = rateLimit.hit(ip, HIGH_LIMIT, LOW_MINUTE)

        assertThat(blocked.allowed).isFalse()
        assertThat(blocked.retryAfter).isEqualTo(ttl.rateLimitMinute)
    }

    @Test
    fun `webhook 가드는 첫 등록만 true, 재등록은 false`() {
        val orderId = UUID.randomUUID()

        assertThat(webhookGuard.markProcessed(orderId)).isTrue()
        assertThat(webhookGuard.markProcessed(orderId)).isFalse()
    }

    @Test
    fun `결제 승인 가드는 첫 시도만 true, 이중 클릭은 false`() {
        val orderId = UUID.randomUUID()

        assertThat(paymentGuard.tryApprove(orderId)).isTrue()
        assertThat(paymentGuard.tryApprove(orderId)).isFalse()
    }

    private fun uniqueIp(): String = UUID.randomUUID().toString()

    companion object {
        private const val REDIS_PORT = 6379
        private const val PER_SECOND = 5
        private const val PER_MINUTE = 20
        private const val HIGH_LIMIT = 1000
        private const val LOW_MINUTE = 3

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
        val redisContainer =
            GenericContainer("redis:8.8.0").apply {
                withExposedPorts(REDIS_PORT)
            }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(REDIS_PORT) }
        }
    }
}
