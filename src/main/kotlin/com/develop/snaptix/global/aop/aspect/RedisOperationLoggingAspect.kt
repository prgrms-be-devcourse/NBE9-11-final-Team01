package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RedisOperation
import com.develop.snaptix.global.aop.type.AspectOrder
import com.develop.snaptix.global.aop.type.RedisAction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Aspect
@Component
@Order(AspectOrder.REDIS_LOGGING)
class RedisOperationLoggingAspect {
    private val logger = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    @Around("@annotation(redisOperation)")
    fun around(
        joinPoint: ProceedingJoinPoint,
        redisOperation: RedisOperation,
    ): Any? {
        val action = redisOperation.action
        val traceId = MDC.get("traceId") ?: "unknown"
        val startTime = System.currentTimeMillis()

        return try {
            val result = joinPoint.proceed()
            val elapsed = System.currentTimeMillis() - startTime

            logger.atInfo {
                message = "Redis operation succeeded"
                payload =
                    buildPayload(
                        action = action,
                        traceId = traceId,
                        result = "SUCCESS",
                        elapsed = elapsed,
                        returnValue = result,
                    )
            }

            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime

            logger.atError {
                message = "Redis operation failed"
                cause = e
                payload =
                    buildPayload(
                        action = action,
                        traceId = traceId,
                        result = "FAIL",
                        elapsed = elapsed,
                        error = e.message,
                    )
            }

            throw e
        }
    }

    private fun buildPayload(
        action: RedisAction,
        traceId: String,
        result: String,
        elapsed: Long,
        returnValue: Any? = null,
        error: String? = null,
    ): Map<String, Any?> = buildMap {
        put("action", action.name)
        put("traceId", traceId)
        put("result", result)
        put("executionTimeMs", elapsed)

        // 재고 차감 액션에만 remaining_stock 포함
        if (action == RedisAction.LUASCRIPT_DECREASE && returnValue != null) {
            put("remaining_stock", returnValue)
        }

        error?.let { put("error", it) }
    }
}
