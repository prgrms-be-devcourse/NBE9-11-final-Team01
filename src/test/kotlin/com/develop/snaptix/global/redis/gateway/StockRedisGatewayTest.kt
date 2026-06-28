package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class StockRedisGatewayTest : IntegrationTestSupport() {
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

    // ── decreaseAndClaim ────────────────────────────────────────────────

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

    // ── compensate ──────────────────────────────────────────────────────

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

    // ── removeClaimed ───────────────────────────────────────────────────

    @Test
    fun `removeClaimed는 claimed에서 orderId를 제거하고 재고는 변경하지 않는다`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()
        gateway.decreaseAndClaim(ZONE_ID, orderId) // stock: 4, claimed: {orderId}

        gateway.removeClaimed(ZONE_ID, orderId)

        // 재고는 차감된 상태 그대로 (compensate와 달리 +1 없음)
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo((SMALL_STOCK - 1).toString())
        // claimed에서는 제거됨
        assertThat(redis.opsForSet().isMember(claimedKey, orderId.toString())).isEqualTo(false)
    }

    @Test
    fun `removeClaimed는 compensate와 달리 재고를 복구하지 않는다`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()
        gateway.decreaseAndClaim(ZONE_ID, orderId) // stock: SMALL_STOCK - 1

        gateway.removeClaimed(ZONE_ID, orderId)

        // compensate라면 SMALL_STOCK으로 복구되지만, removeClaimed는 그대로
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo((SMALL_STOCK - 1).toString())
    }

    @Test
    fun `removeClaimed는 claimed에 없는 orderId에 대해 멱등하게 동작한다`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()
        // claimed에 추가하지 않음

        // 예외 없이 정상 완료
        gateway.removeClaimed(ZONE_ID, orderId)

        assertThat(redis.opsForValue().get(stockKey)).isEqualTo(SMALL_STOCK.toString())
        assertThat(redis.opsForSet().isMember(claimedKey, orderId.toString())).isEqualTo(false)
    }

    @Test
    fun `removeClaimed를 2회 호출해도 재고에 영향이 없다`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val orderId = UUID.randomUUID()
        gateway.decreaseAndClaim(ZONE_ID, orderId)

        gateway.removeClaimed(ZONE_ID, orderId)
        gateway.removeClaimed(ZONE_ID, orderId) // 2회차 — 멱등

        assertThat(redis.opsForValue().get(stockKey)).isEqualTo((SMALL_STOCK - 1).toString())
        assertThat(redis.opsForSet().isMember(claimedKey, orderId.toString())).isEqualTo(false)
    }

    @Test
    fun `removeClaimed는 다른 orderId는 claimed에서 제거하지 않는다`() {
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        val targetOrder = UUID.randomUUID()
        val otherOrder = UUID.randomUUID()
        gateway.decreaseAndClaim(ZONE_ID, targetOrder)
        gateway.decreaseAndClaim(ZONE_ID, otherOrder)

        gateway.removeClaimed(ZONE_ID, targetOrder)

        assertThat(redis.opsForSet().isMember(claimedKey, targetOrder.toString())).isEqualTo(false)
        assertThat(redis.opsForSet().isMember(claimedKey, otherOrder.toString())).isEqualTo(true)
    }

    // ── get / getAll / correctStock / rebuild / isClaimed ───────────────

    @Test
    fun `get은 키 존재 시 Int, 부재 시 null`() {
        assertThat(gateway.get(ZONE_ID)).isNull()

        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())

        assertThat(gateway.get(ZONE_ID)).isEqualTo(SMALL_STOCK)
    }

    @Test
    fun `getAll은 여러 zone 재고를 한번에 조회하고 키 부재 시 null을 반환한다`() {
        val anotherZoneId = 2L
        redis.opsForValue().set(stockKey, SMALL_STOCK.toString())
        redis.opsForValue().set(keys.stock(anotherZoneId), "0")

        val stocks = gateway.getAll(listOf(ZONE_ID, anotherZoneId, 3L))

        assertThat(stocks).containsEntry(ZONE_ID, SMALL_STOCK)
        assertThat(stocks).containsEntry(anotherZoneId, 0)
        assertThat(stocks).containsEntry(3L, null)
    }

    @Test
    fun `correctStock은 stock만 SET하고 claimed는 건드리지 않는다`() {
        redis.opsForValue().set(stockKey, "3")
        redis.opsForSet().add(claimedKey, "existing-order")

        gateway.correctStock(ZONE_ID, SMALL_STOCK)

        assertThat(redis.opsForValue().get(stockKey)).isEqualTo(SMALL_STOCK.toString())
        assertThat(redis.opsForSet().isMember(claimedKey, "existing-order")).isEqualTo(true)
    }

    @Test
    fun `rebuild는 stock SET과 claimed 덮어쓰기를 수행한다`() {
        redis.opsForValue().set(stockKey, "999")
        redis.opsForSet().add(claimedKey, "old-order")
        val o1 = UUID.randomUUID()
        val o2 = UUID.randomUUID()

        gateway.rebuild(ZONE_ID, REBUILD_STOCK, listOf(o1, o2))

        assertThat(redis.opsForValue().get(stockKey)).isEqualTo(REBUILD_STOCK.toString())
        assertThat(redis.opsForSet().size(claimedKey)).isEqualTo(2L)
        assertThat(redis.opsForSet().isMember(claimedKey, o1.toString())).isEqualTo(true)
        assertThat(redis.opsForSet().isMember(claimedKey, o2.toString())).isEqualTo(true)
        assertThat(redis.opsForSet().isMember(claimedKey, "old-order")).isEqualTo(false)
    }

    @Test
    fun `rebuild는 claimed가 비어도 stock SET 후 claimed를 비운다`() {
        redis.opsForSet().add(claimedKey, "old-order")

        gateway.rebuild(ZONE_ID, REBUILD_STOCK, emptyList())

        assertThat(redis.opsForValue().get(stockKey)).isEqualTo(REBUILD_STOCK.toString())
        assertThat(redis.opsForSet().size(claimedKey)).isEqualTo(0L)
    }

    @Test
    fun `isClaimed는 orderId가 claimed 집합에 존재하면 true를 반환한다`() {
        val orderId = UUID.randomUUID()
        redis.opsForSet().add(claimedKey, orderId.toString())

        val result = gateway.isClaimed(ZONE_ID, orderId)

        assertThat(result).isTrue()
    }

    @Test
    fun `isClaimed는 orderId가 claimed 집합에 없으면 false를 반환한다`() {
        val orderId = UUID.randomUUID()
        // claimed 집합에 넣지 않음

        val result = gateway.isClaimed(ZONE_ID, orderId)

        assertThat(result).isFalse()
    }

    // ── concurrency ─────────────────────────────────────────────────────

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
        private const val REBUILD_STOCK = 60
        private const val CONCURRENT_ATTEMPTS = 1000
        private const val THREAD_POOL_SIZE = 32
        private const val AWAIT_SECONDS = 30L
    }
}
