package com.develop.snaptix.global.redis.support

import com.develop.snaptix.global.aop.type.RedisAction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * Redis 연산 구조화 로깅(JSON). ResilientRedisExecutor가 단일 지점에서 호출한다.
 *
 * 필수 필드: action / result / executionTimeMs / traceId(MDC).
 * `LUASCRIPT_DECREASE`에만 remaining_stock 포함(기존 RedisOperationLoggingAspect와 동일 포맷).
 */
@Component
class RedisActionLogger {
    fun success(
        action: RedisAction,
        executionTimeMs: Long,
        result: Any?,
    ) {
        log.info {
            jsonLog(
                "action" to action.name,
                "result" to "SUCCESS",
                "executionTimeMs" to executionTimeMs,
                "traceId" to currentTraceId(),
                "remaining_stock" to remainingStock(action, result),
            )
        }
    }

    fun failure(
        action: RedisAction,
        executionTimeMs: Long,
        error: Throwable,
    ) {
        log.error(error) {
            jsonLog(
                "action" to action.name,
                "result" to "FAIL",
                "executionTimeMs" to executionTimeMs,
                "traceId" to currentTraceId(),
                "error" to error.message,
            )
        }
    }

    fun circuitOpen(
        action: RedisAction,
        error: Throwable,
    ) {
        log.warn(error) {
            jsonLog(
                "action" to action.name,
                "result" to "CIRCUIT_OPEN",
                "traceId" to currentTraceId(),
                "error" to error.message,
            )
        }
    }

    /** remaining_stock는 재고 차감 액션에만 포함. 그 외엔 null → jsonLog에서 생략. */
    private fun remainingStock(
        action: RedisAction,
        result: Any?,
    ): Any? = result.takeIf { action == RedisAction.LUASCRIPT_DECREASE }

    private fun currentTraceId(): String = MDC.get(TRACE_ID_KEY) ?: UNKNOWN_TRACE_ID

    /** null 값 필드는 생략하고 JSON 문자열로 직렬화. */
    private fun jsonLog(vararg pairs: Pair<String, Any?>): String = pairs
        .filter { it.second != null }
        .joinToString(separator = ", ", prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\": \"$value\""
        }

    companion object {
        private const val TRACE_ID_KEY = "traceId"
        private const val UNKNOWN_TRACE_ID = "unknown"
        private val log = KotlinLogging.logger {}
    }
}
