package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
class IdempotencyRedisGatewayTest {
    @Autowired
    private lateinit var gateway: IdempotencyRedisGateway

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var ttl: RedisTtlProperties

    private val keys = RedisKeyFactory()

    @Test
    fun `최초 선점은 성공하고 값은 orderId, 재시도는 충돌로 false`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)

        assertThat(gateway.tryAcquire(USER_ID, eventId, orderId)).isEqualTo(true)
        assertThat(redis.opsForValue().get(key)).isEqualTo(orderId.toString())
        assertThat(gateway.tryAcquire(USER_ID, eventId, UUID.randomUUID())).isEqualTo(false)
    }

    @Test
    fun `재앵커링은 TTL을 홀드 이하로 단축한다`() {
        val eventId = UUID.randomUUID()
        gateway.tryAcquire(USER_ID, eventId, UUID.randomUUID())

        gateway.reanchor(USER_ID, eventId)

        val remaining = redis.getExpire(keys.idempotency(USER_ID, eventId))
        assertThat(remaining).isGreaterThan(0L).isLessThanOrEqualTo(ttl.orderHold.seconds)
    }

    @Test
    fun `compare-and-delete는 값이 일치하면 삭제한다`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)
        gateway.tryAcquire(USER_ID, eventId, orderId)

        val deleted = gateway.compareAndDelete(USER_ID, eventId, orderId)

        assertThat(deleted).isEqualTo(true)
        assertThat(redis.hasKey(key)).isEqualTo(false)
    }

    @Test
    fun `compare-and-delete는 값이 다르면 키를 보존한다`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)
        gateway.tryAcquire(USER_ID, eventId, orderId)

        val deleted = gateway.compareAndDelete(USER_ID, eventId, UUID.randomUUID())

        assertThat(deleted).isEqualTo(false)
        assertThat(redis.hasKey(key)).isEqualTo(true)
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val USER_ID = 1L

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
