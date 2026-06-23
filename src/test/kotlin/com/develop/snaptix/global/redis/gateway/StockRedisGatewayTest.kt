package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.key.RedisKeyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@Testcontainers
class StockRedisGatewayTest {
    @Autowired
    private lateinit var gateway: StockRedisGateway

    @Autowired
    private lateinit var redis: StringRedisTemplate

    private val keys = RedisKeyFactory()
    private val stockKey = keys.stock(ZONE_ID)
    private val claimedKey = keys.claimed(ZONE_ID)

    @BeforeEach
    fun cleanUp() {
        redis.delete(listOf(stockKey, claimedKey))
    }

    @Test
    fun `차감 성공 시 OK 반환, 재고 1 감소, claimed에 orderId 등록`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()

        val result = gateway.decreaseAndClaim(ZONE_ID, orderId)

        assertThat(result).isEqualTo(DecreaseResult.OK)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo((SMALL_STOCK - 1).toString())
        assertThat(redis.opsForSet().isMember(claimedKey, orderId.toString())).isEqualTo(true)
    }

    @Test
    fun `같은 orderId 재차감 시 ALREADY 반환, 추가 차감 없음`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()

        gateway.decreaseAndClaim(ZONE_ID, orderId)
        val second = gateway.decreaseAndClaim(ZONE_ID, orderId)

        assertThat(second).isEqualTo(DecreaseResult.ALREADY)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo((SMALL_STOCK - 1).toString())
    }

    @Test
    fun `재고 0이면 SOLD_OUT 반환, 차감 없음`() {
        redis.opsForValue().set(stockKey, "0")

        val result = gateway.decreaseAndClaim(ZONE_ID, UUID.randomUUID())

        assertThat(result).isEqualTo(DecreaseResult.SOLD_OUT)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo("0")
    }

    @Test
    fun `보상은 claimed에 있을 때만 +1 및 제거, 재호출 시 이중 보상하지 않음`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()
        gateway.decreaseAndClaim(ZONE_ID, orderId)

        val first = gateway.compensate(ZONE_ID, orderId)
        assertThat(first).isEqualTo(true)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo(SMALL_STOCK.toString())
        assertThat(redis.opsForSet().isMember(claimedKey, orderId.toString())).isEqualTo(false)

        val second = gateway.compensate(ZONE_ID, orderId)
        assertThat(second).isEqualTo(false)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo(SMALL_STOCK.toString())
    }

    @Test
    fun `초기 100 동시 1000 차감 시 정확히 100건만 성공하고 최종 재고는 0`() {
        redis.opsForValue().set(stockKey, INITIAL_STOCK.toString())
        val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val latch = CountDownLatch(CONCURRENT_ATTEMPTS)
        val okCount = AtomicInteger(0)

        repeat(CONCURRENT_ATTEMPTS) {
            pool.submit {
                try {
                    if (gateway.decreaseAndClaim(ZONE_ID, UUID.randomUUID()) == DecreaseResult.OK) {
                        okCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        pool.shutdown()

        assertThat(okCount.get()).isEqualTo(INITIAL_STOCK)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo("0")
        assertThat(redis.opsForSet().size(claimedKey)).isEqualTo(INITIAL_STOCK.toLong())
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val ZONE_ID = 1L
        private const val SMALL_STOCK = 5
        private const val INITIAL_STOCK = 100
        private const val CONCURRENT_ATTEMPTS = 1000
        private const val THREAD_POOL_SIZE = 32
        private const val AWAIT_SECONDS = 30L

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
