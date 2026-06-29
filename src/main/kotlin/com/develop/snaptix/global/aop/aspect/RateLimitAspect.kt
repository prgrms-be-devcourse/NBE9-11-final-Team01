package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RateLimit
import com.develop.snaptix.global.aop.type.AspectOrder
import com.develop.snaptix.global.exception.redis.RateLimitExceededException
import com.develop.snaptix.global.redis.gateway.RateLimitRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * @RateLimit 메서드에 IP 기반 rate limit을 적용하는 Aspect.
 *
 * 실제 카운터 연산(INCR + 최초 EXPIRE 원자 Lua)은 [RateLimitRedisGateway]에 위임한다.
 * 아스펙트는 IP 추출·한도 전달·차단 예외·fail-open 정책(경계)만 담당한다(메인 명세서 §4.5).
 *
 * 실행 순서: CB(1) → [RateLimit(2)] → Idempotency(3) → RedisLogging(4)
 */
@Aspect
@Component
@Order(AspectOrder.RATE_LIMIT)
class RateLimitAspect(
    private val rateLimitGateway: RateLimitRedisGateway,
) {
    private val logger = KotlinLogging.logger {}

    @Around("@annotation(rateLimit)")
    fun around(
        joinPoint: ProceedingJoinPoint,
        rateLimit: RateLimit,
    ): Any? {
        enforceLimit(extractIp(), rateLimit.limitPerSecond, rateLimit.limitPerMinute)
        return joinPoint.proceed()
    }

    /**
     * IP 단위 rate limit 적용(게이트웨이 위임).
     * DataAccessException 발생 시 fail-open(skip) — 서킷 OPEN은 상위 CB 아스펙트가 선차단.
     */
    private fun enforceLimit(
        ip: String,
        limitPerSecond: Int,
        limitPerMinute: Int,
    ) {
        val result =
            try {
                rateLimitGateway.hit(ip, limitPerSecond, limitPerMinute)
            } catch (e: DataAccessException) {
                logger.atWarn {
                    message = "Redis unavailable, skipping rate limit"
                    cause = e
                    payload =
                        mapOf(
                            "action" to "RATE_LIMIT_CHECK",
                            "result" to "SKIP_FAIL_OPEN",
                            "ip" to ip,
                            "traceId" to traceId(),
                        )
                }
                return
            }

        if (!result.allowed) {
            val retryAfterSeconds = result.retryAfter?.seconds ?: 0L
            logger.atWarn {
                message = "Rate limit exceeded"
                payload =
                    mapOf(
                        "action" to "RATE_LIMIT_CHECK",
                        "result" to "BLOCKED",
                        "ip" to ip,
                        "retryAfterSeconds" to retryAfterSeconds,
                        "traceId" to traceId(),
                    )
            }
            throw RateLimitExceededException(retryAfterSeconds = retryAfterSeconds)
        }
    }

    private fun traceId(): String = MDC.get("traceId") ?: "unknown"

    private fun extractIp(): String {
        val request =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
                ?.request
                ?: return "unknown"

        return request
            .getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?: request.remoteAddr
    }
}
