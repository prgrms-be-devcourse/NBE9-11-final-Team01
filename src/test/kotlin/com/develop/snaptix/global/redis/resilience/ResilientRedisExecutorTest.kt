package com.develop.snaptix.global.redis.resilience

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.support.RedisActionLogger
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException

class ResilientRedisExecutorTest {
    private val actionLogger = mockk<RedisActionLogger>(relaxed = true)
    private val registry = CircuitBreakerRegistry.ofDefaults()
    private val executor = ResilientRedisExecutor(registry, actionLogger)

    @Test
    fun `성공 시 결과를 반환하고 success 로그를 남긴다`() {
        val result = executor.execute(RedisAction.IDEMPOTENCY_CHECK) { "OK" }

        assertThat(result).isEqualTo("OK")
        verify(exactly = 1) { actionLogger.success(RedisAction.IDEMPOTENCY_CHECK, any(), "OK") }
    }

    @Test
    fun `서킷이 OPEN이면 RedisUnavailableException을 던지고 circuitOpen 로그를 남긴다`() {
        registry.circuitBreaker("redis").transitionToOpenState()

        assertThatThrownBy {
            executor.execute(RedisAction.IDEMPOTENCY_CHECK) { "OK" }
        }.isInstanceOf(RedisUnavailableException::class.java)

        verify(exactly = 1) { actionLogger.circuitOpen(RedisAction.IDEMPOTENCY_CHECK, any()) }
    }

    @Test
    fun `Redis 오류는 failure 로그 후 그대로 재전파된다`() {
        assertThatThrownBy {
            executor.execute(RedisAction.IDEMPOTENCY_CHECK) {
                throw QueryTimeoutException("Redis timeout")
            }
        }.isInstanceOf(QueryTimeoutException::class.java)

        verify(exactly = 1) { actionLogger.failure(RedisAction.IDEMPOTENCY_CHECK, any(), any()) }
    }
}
