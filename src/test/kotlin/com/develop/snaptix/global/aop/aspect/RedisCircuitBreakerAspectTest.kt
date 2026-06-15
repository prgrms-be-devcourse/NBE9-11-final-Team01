package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RedisCircuitBreaker
import com.develop.snaptix.global.exception.redis.RateLimitExceededException
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Duration

class RedisCircuitBreakerAspectTest {
    // AspectJ 포인트컷이 어노테이션을 읽으려면 실제 클래스가 필요 → spyk로 감싸 동작 제어
    open class TestTarget {
        @RedisCircuitBreaker
        open fun call(): String = "success"
    }

    private lateinit var registry: CircuitBreakerRegistry
    private lateinit var aspect: RedisCircuitBreakerAspect
    private lateinit var target: TestTarget
    private lateinit var proxy: TestTarget

    @BeforeEach
    fun setUp() {
        // 테스트 전용 설정 — 5회 윈도우, 60% 실패율(3회)이면 OPEN
        val config =
            CircuitBreakerConfig
                .custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .failureRateThreshold(60f)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(false) // 수동 전환으로 테스트 결정론 확보
                .recordException(RedisExceptionPredicate())
                .ignoreException { it is com.develop.snaptix.global.exception.BusinessException }
                .build()

        registry = CircuitBreakerRegistry.of(config)
        aspect = RedisCircuitBreakerAspect(registry)

        target = spyk(TestTarget())
        val factory = AspectJProxyFactory(target)
        factory.addAspect(aspect)
        proxy = factory.getProxy()
    }

    // ──────────────────────────────────────────────────────────
    // CLOSED 상태 — 정상 흐름
    // ──────────────────────────────────────────────────────────

    @Test
    fun `CB CLOSED 상태에서 정상 호출 시 결과를 반환한다`() {
        every { target.call() } returns "success"

        val result = proxy.call()

        assertThat(result).isEqualTo("success")
        verify(exactly = 1) { target.call() }
    }

    @Test
    fun `CB CLOSED 상태에서 DataAccessException 발생 시 예외가 재전파된다`() {
        every { target.call() } throws DataAccessResourceFailureException("Redis down")

        assertThatThrownBy { proxy.call() }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
    }

    // ──────────────────────────────────────────────────────────
    // CLOSED → OPEN 전환
    // ──────────────────────────────────────────────────────────

    @Test
    fun `DataAccessException이 임계값(5회 중 3회)을 초과하면 CB가 OPEN으로 전환된다`() {
        every { target.call() } throws DataAccessResourceFailureException("Redis down")

        // 슬라이딩 윈도우를 채워야 실패율 계산 시작 → 5회 모두 실패(100% > 60%)
        repeat(5) { runCatching { proxy.call() } }

        assertThat(registry.circuitBreaker("redis").state)
            .isEqualTo(CircuitBreaker.State.OPEN)
    }

    @Test
    fun `비즈니스 예외는 CB 실패 카운트에 포함되지 않아 CB가 OPEN되지 않는다`() {
        // RateLimitExceededException은 BusinessException 하위 → ignoreException 대상
        every { target.call() } throws RateLimitExceededException()

        repeat(10) { runCatching { proxy.call() } }

        assertThat(registry.circuitBreaker("redis").state)
            .isEqualTo(CircuitBreaker.State.CLOSED)
    }

    // ──────────────────────────────────────────────────────────
    // OPEN 상태 — 즉시 차단
    // ──────────────────────────────────────────────────────────

    @Test
    fun `CB OPEN 상태에서 요청 시 RedisUnavailableException이 발생한다`() {
        registry.circuitBreaker("redis").transitionToOpenState()
        every { target.call() } returns "success"

        assertThatThrownBy { proxy.call() }
            .isInstanceOf(RedisUnavailableException::class.java)
    }

    @Test
    fun `CB OPEN 상태에서 proceed()는 호출되지 않는다`() {
        registry.circuitBreaker("redis").transitionToOpenState()
        every { target.call() } returns "success"

        runCatching { proxy.call() }

        // 실제 대상 메서드에 도달하지 않아야 함
        verify(exactly = 0) { target.call() }
    }

    // ──────────────────────────────────────────────────────────
    // HALF_OPEN 상태 — 프로브 결과에 따른 전환
    // ──────────────────────────────────────────────────────────

    @Test
    fun `CB HALF_OPEN 상태에서 프로브 성공 시 CLOSED로 전환된다`() {
        registry.circuitBreaker("redis").transitionToOpenState()
        registry.circuitBreaker("redis").transitionToHalfOpenState()
        every { target.call() } returns "success"

        // permittedNumberOfCallsInHalfOpenState = 2 → 2회 성공 시 CLOSED
        repeat(2) { proxy.call() }

        assertThat(registry.circuitBreaker("redis").state)
            .isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `CB HALF_OPEN 상태에서 DataAccessException 발생 시 OPEN으로 복귀한다`() {
        registry.circuitBreaker("redis").transitionToOpenState()
        registry.circuitBreaker("redis").transitionToHalfOpenState()
        every { target.call() } throws DataAccessResourceFailureException("Redis still down")

        repeat(2) { runCatching { proxy.call() } }

        assertThat(registry.circuitBreaker("redis").state)
            .isEqualTo(CircuitBreaker.State.OPEN)
    }

    @Test
    fun `CB HALF_OPEN 상태에서 DataAccessException 발생 시 예외가 재전파된다`() {
        registry.circuitBreaker("redis").transitionToOpenState()
        registry.circuitBreaker("redis").transitionToHalfOpenState()
        every { target.call() } throws DataAccessResourceFailureException("Redis still down")

        assertThatThrownBy { proxy.call() }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
    }
}
