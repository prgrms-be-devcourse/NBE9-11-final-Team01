package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.type.AspectOrder
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Aspect
@Component
@Order(AspectOrder.CIRCUIT_BREAKER)
class RedisCircuitBreakerAspect(
    circuitBreakerRegistry: CircuitBreakerRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("redis")

    @Around("@annotation(com.develop.snaptix.global.aop.annotation.RedisCircuitBreaker)")
    fun around(joinPoint: ProceedingJoinPoint): Any? = try {
        circuitBreaker.executeCheckedSupplier { joinPoint.proceed() }
    } catch (e: CallNotPermittedException) {
        logger.atWarn {
            message = "Circuit breaker is OPEN, rejecting request"
            cause = e
            payload =
                mapOf(
                    "action" to "CB_STATE_CHECK",
                    "state" to circuitBreaker.state.name,
                )
        }
        throw RedisUnavailableException()
    }
    // DataAccessException은 executeCheckedSupplier가 CB에 실패로 기록한 뒤 그대로 재전파
    // → 하위 Aspect(RateLimit, Idempotency)의 catch (e: DataAccessException)이 처리
}
