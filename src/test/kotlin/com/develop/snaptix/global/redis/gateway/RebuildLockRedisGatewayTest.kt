package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class RebuildLockRedisGatewayTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var gateway: RebuildLockRedisGateway

    private val ttl: Duration = Duration.ofMinutes(5)

    /** 각 테스트가 독립 락 키를 갖도록 매번 새로 생성한다. */
    private fun lockKey() = "rebuild:lock:test:${UUID.randomUUID()}"

    @Test
    fun `최초 획득은 성공하고 보유 중 타 토큰은 실패한다(SET NX)`() {
        val key = lockKey()
        val tokenA = UUID.randomUUID().toString()
        val tokenB = UUID.randomUUID().toString()

        assertThat(gateway.tryAcquire(key, tokenA, ttl)).isTrue()
        assertThat(gateway.tryAcquire(key, tokenB, ttl)).isFalse()
    }

    @Test
    fun `compare-and-delete는 보유자 토큰일 때만 해제한다`() {
        val key = lockKey()
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        gateway.tryAcquire(key, owner, ttl)

        // 토큰 불일치 해제 → 무효(여전히 owner 보유)
        gateway.release(key, other)
        assertThat(gateway.tryAcquire(key, other, ttl)).isFalse()

        // 보유자 해제 → 이후 타 토큰 획득 가능
        gateway.release(key, owner)
        assertThat(gateway.tryAcquire(key, other, ttl)).isTrue()
    }

    @Test
    fun `동시에 같은 키를 노려도 정확히 하나만 획득한다`() {
        val key = lockKey()
        val threads = 20
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val acquired = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                val token = UUID.randomUUID().toString()
                start.await()
                if (gateway.tryAcquire(key, token, ttl)) acquired.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        assertThat(acquired.get()).isEqualTo(1) // SET NX 원자성 → 단 하나만 성공
    }
}
