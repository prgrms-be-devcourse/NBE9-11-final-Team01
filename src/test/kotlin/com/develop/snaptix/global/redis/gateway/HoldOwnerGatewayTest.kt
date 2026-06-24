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
class HoldOwnerGatewayTest {
    @Autowired
    private lateinit var holdGateway: OrderHoldRedisGateway

    @Autowired
    private lateinit var ownershipGateway: OwnershipRedisGateway

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var ttl: RedisTtlProperties

    private val keys = RedisKeyFactory()

    @Test
    fun `홀드 생성 시 존재하고 TTL은 홀드 이하, 삭제 시 사라진다`() {
        val orderId = UUID.randomUUID()

        holdGateway.create(orderId)

        assertThat(holdGateway.exists(orderId)).isEqualTo(true)
        val remaining = redis.getExpire(keys.orderHold(orderId))
        assertThat(remaining).isGreaterThan(0L).isLessThanOrEqualTo(ttl.orderHold.seconds)

        holdGateway.delete(orderId)
        assertThat(holdGateway.exists(orderId)).isEqualTo(false)
    }

    @Test
    fun `소유권 기록 후 조회되고 TTL은 인게스트 봉투 이하, 삭제 시 null`() {
        val orderId = UUID.randomUUID()

        ownershipGateway.set(orderId, USER_ID)

        assertThat(ownershipGateway.ownerOf(orderId)).isEqualTo(USER_ID)
        val remaining = redis.getExpire(keys.orderOwner(orderId))
        assertThat(remaining).isGreaterThan(0L).isLessThanOrEqualTo(ttl.ingestEnvelope.seconds)

        ownershipGateway.delete(orderId)
        assertThat(ownershipGateway.ownerOf(orderId)).isNull()
    }

    @Test
    fun `소유권이 없는 주문은 ownerOf가 null`() {
        assertThat(ownershipGateway.ownerOf(UUID.randomUUID())).isNull()
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val USER_ID = 7L

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
