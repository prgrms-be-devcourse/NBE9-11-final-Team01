package com.develop.snaptix.domain.order.observability

import com.develop.snaptix.global.aop.type.AspectOrder
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * @LogAction 메서드에 구조화 로그를 자동 주입하는 Aspect.
 *
 * ## 로그 필드 (명세서 §14 In-scope)
 * | 필드            | 출처                    |
 * |----------------|------------------------|
 * | traceId        | MDC (전역 Filter 주입)  |
 * | userId         | MDC (서비스 진입부 주입) |
 * | eventId        | MDC (서비스 진입부 주입) |
 * | zoneId         | MDC (서비스 진입부 주입) |
 * | action         | @LogAction.action 값    |
 * | result         | SUCCESS / ERROR         |
 * | executionTimeMs| 실제 메서드 실행 시간   |
 *
 * ## 실행 순서 (AspectOrder)
 * CB(1) → RateLimit(2) → Idempotency(3) → RedisLogging(4) → CacheAside(5) → [OrderLogging(6)]
 *
 * ## 멱등 409 특례
 * BusinessException / IdempotencyConflictException 은 WARN 수준으로 낮춘다 (Log Storm 방지).
 */
@Aspect
@Component
@Order(AspectOrder.ORDER_LOGGING)
class OrderLoggingAspect {
    private val log = KotlinLogging.logger {}

    /**
     * [ProceedingJoinPoint.proceed]는 `throws Throwable` 로 선언되어 있어
     * Throwable 포착이 구조적으로 불가피하다 — IdempotencyAspect 동일 패턴.
     */
    @Suppress("TooGenericExceptionCaught") // proceed()가 Throwable 선언 — AOP around 의도적 포착
    @Around("@annotation(logAction)")
    fun around(
        joinPoint: ProceedingJoinPoint,
        logAction: LogAction,
    ): Any? {
        val action = logAction.action
        val start = System.currentTimeMillis()

        return try {
            val result = joinPoint.proceed()
            logInfo(action, elapsed(start))
            result
        } catch (ex: Throwable) {
            logError(action, elapsed(start), ex)
            throw ex
        }
    }

    // ── 로그 출력 ──────────────────────────────────────────────────────────

    private fun logInfo(
        action: String,
        ms: Long,
    ) {
        log.atInfo {
            message = "Order action completed"
            payload = buildPayload(action, "SUCCESS", ms)
        }
    }

    /**
     * 멱등 충돌(409)·BusinessException 은 예상 거부 흐름이므로 WARN 으로 낮춘다.
     * 그 외 예외는 ERROR.
     */
    private fun logError(
        action: String,
        ms: Long,
        ex: Throwable,
    ) {
        val payload = buildPayload(action, "ERROR", ms) + ("errorType" to ex.javaClass.simpleName)

        if (ex.javaClass.simpleName == "IdempotencyConflictException" ||
            ex.javaClass.simpleName == "BusinessException"
        ) {
            log.atWarn {
                message = "Order action rejected"
                cause = ex
                this.payload = payload
            }
        } else {
            log.atError {
                message = "Order action failed"
                cause = ex
                this.payload = payload
            }
        }
    }

    // ── MDC 필드 수집 ─────────────────────────────────────────────────────

    private fun buildPayload(
        action: String,
        result: String,
        ms: Long,
    ): Map<String, Any?> = mapOf(
        "action" to action,
        "result" to result,
        "executionTimeMs" to ms,
        "traceId" to (MDC.get(OrderMdc.TRACE_ID) ?: "unknown"),
        "userId" to MDC.get(OrderMdc.USER_ID),
        "eventId" to MDC.get(OrderMdc.EVENT_ID),
        "zoneId" to MDC.get(OrderMdc.ZONE_ID),
    )

    private fun elapsed(start: Long) = System.currentTimeMillis() - start
}
