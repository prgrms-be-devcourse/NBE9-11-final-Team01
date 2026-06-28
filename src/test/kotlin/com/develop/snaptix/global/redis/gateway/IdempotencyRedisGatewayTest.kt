package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

@SpringBootTest
class IdempotencyRedisGatewayTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var gateway: IdempotencyRedisGateway

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var ttl: RedisTtlProperties

    private val keys = RedisKeyFactory()

    // ── tryAcquire ──────────────────────────────────────────────────────

    @Test
    fun `최초 선점은 성공하고 값은 orderId, 재시도는 충돌로 false`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)

        assertThat(gateway.tryAcquire(USER_ID, eventId, orderId)).isEqualTo(true)
        assertThat(redis.opsForValue().get(key)).isEqualTo(orderId.toString())
        assertThat(gateway.tryAcquire(USER_ID, eventId, UUID.randomUUID())).isEqualTo(false)
    }

    // ── reanchor ────────────────────────────────────────────────────────

    @Test
    fun `재앵커링은 TTL을 홀드 이하로 단축한다`() {
        val eventId = UUID.randomUUID()
        gateway.tryAcquire(USER_ID, eventId, UUID.randomUUID())

        gateway.reanchor(USER_ID, eventId)

        val remaining = redis.getExpire(keys.idempotency(USER_ID, eventId))
        assertThat(remaining).isGreaterThan(0L).isLessThanOrEqualTo(ttl.orderHold.seconds)
    }

    // ── compareAndDelete ────────────────────────────────────────────────

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

    // ── markCompleted ───────────────────────────────────────────────────

    @Test
    fun `markCompleted는 키가 존재하면 값을 COMPLETED로 갱신하고 true를 반환한다`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)
        gateway.tryAcquire(USER_ID, eventId, orderId)

        val result = gateway.markCompleted(USER_ID, eventId)

        assertThat(result).isTrue()
        assertThat(redis.opsForValue().get(key)).isEqualTo(COMPLETED_VALUE)
    }

    @Test
    fun `markCompleted는 키가 없으면 no-op이고 false를 반환한다`() {
        val eventId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)
        // tryAcquire 없이 바로 호출

        val result = gateway.markCompleted(USER_ID, eventId)

        assertThat(result).isFalse()
        assertThat(redis.hasKey(key)).isFalse()
    }

    @Test
    fun `markCompleted는 TTL을 유지한다 (KEEPTTL)`() {
        val eventId = UUID.randomUUID()
        gateway.tryAcquire(USER_ID, eventId, UUID.randomUUID())
        gateway.reanchor(USER_ID, eventId) // TTL → orderHold (300s)

        gateway.markCompleted(USER_ID, eventId)

        val ttlAfter = redis.getExpire(keys.idempotency(USER_ID, eventId))
        assertThat(ttlAfter)
            .isGreaterThan(0L)
            .isLessThanOrEqualTo(ttl.orderHold.seconds)
    }

    @Test
    fun `markCompleted 후 tryAcquire는 키가 남아 있어 재획득에 실패한다`() {
        // 결제 확정 후 재구매 시도가 차단되어야 함을 보장
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        gateway.tryAcquire(USER_ID, eventId, orderId)
        gateway.markCompleted(USER_ID, eventId)

        val acquired = gateway.tryAcquire(USER_ID, eventId, UUID.randomUUID())

        assertThat(acquired).isFalse()
    }

    @Test
    fun `markCompleted를 2회 호출해도 멱등하다`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)
        gateway.tryAcquire(USER_ID, eventId, orderId)

        gateway.markCompleted(USER_ID, eventId)
        val secondResult = gateway.markCompleted(USER_ID, eventId)

        assertThat(secondResult).isTrue()
        assertThat(redis.opsForValue().get(key)).isEqualTo(COMPLETED_VALUE)
    }

    @Test
    fun `markCompleted 후 compareAndDelete는 값 불일치로 키를 보존한다`() {
        // COMPLETED로 바뀐 키는 orderId compare-and-delete 시 값이 달라 삭제되지 않아야 함
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val key = keys.idempotency(USER_ID, eventId)
        gateway.tryAcquire(USER_ID, eventId, orderId)
        gateway.markCompleted(USER_ID, eventId)

        val deleted = gateway.compareAndDelete(USER_ID, eventId, orderId)

        assertThat(deleted).isFalse()
        assertThat(redis.opsForValue().get(key)).isEqualTo(COMPLETED_VALUE)
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val USER_ID = 1L
        private const val COMPLETED_VALUE = "COMPLETED"
    }
}
