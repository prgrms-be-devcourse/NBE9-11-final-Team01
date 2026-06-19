package com.develop.snaptix.global.aop.aspect
import com.develop.snaptix.global.aop.annotation.RateLimit
import com.develop.snaptix.global.aop.type.AspectOrder
import com.develop.snaptix.global.exception.redis.RateLimitExceededException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Aspect
@Component
@Order(AspectOrder.RATE_LIMIT)
class RateLimitAspect(
    private val redisTemplate: StringRedisTemplate,
) {
    private val logger = KotlinLogging.logger {}

    // INCR 후 최초 호출이면 EXPIRE 설정 — 원자적 처리
    private val rateLimitScript =
        RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """.trimIndent(),
            Long::class.java,
        )

    @Around("@annotation(rateLimit)")
    fun around(
        joinPoint: ProceedingJoinPoint,
        rateLimit: RateLimit,
    ): Any? {
        val ip = extractIp()
        val traceId = MDC.get("traceId") ?: "unknown"

        checkLimit(
            ip = ip,
            traceId = traceId,
            keySuffix = "sec",
            ttlSeconds = 1L,
            limit = rateLimit.limitPerSecond,
            limitType = "PER_SECOND",
        )
        checkLimit(
            ip = ip,
            traceId = traceId,
            keySuffix = "min",
            ttlSeconds = 60L,
            limit = rateLimit.limitPerMinute,
            limitType = "PER_MINUTE",
        )

        return joinPoint.proceed()
    }

    private fun checkLimit(
        ip: String,
        traceId: String,
        keySuffix: String,
        ttlSeconds: Long,
        limit: Int,
        limitType: String,
    ) {
        val key = "rate_limit:$ip:$keySuffix"
        val count =
            try {
                redisTemplate.execute(
                    rateLimitScript,
                    listOf(key),
                    ttlSeconds.toString(),
                ) ?: 0L
            } catch (e: DataAccessException) {
                logger.atWarn {
                    message = "Redis unavailable, skipping rate limit"
                    cause = e
                    payload =
                        mapOf(
                            "action" to "RATE_LIMIT_CHECK",
                            "traceId" to traceId,
                            "ip" to ip,
                            "limitType" to limitType,
                            "result" to "SKIP",
                        )
                }
                return // 검사 스킵, joinPoint.proceed()로 진행
            }

        if (count > limit) {
            logger.atWarn {
                message = "Rate limit exceeded"
                payload =
                    mapOf(
                        "action" to "RATE_LIMIT_CHECK",
                        "traceId" to traceId,
                        "ip" to ip,
                        "limitType" to limitType,
                        "count" to count,
                        "limit" to limit,
                        "result" to "BLOCKED",
                    )
            }
            throw RateLimitExceededException(retryAfterSeconds = ttlSeconds)
        }
    }

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
