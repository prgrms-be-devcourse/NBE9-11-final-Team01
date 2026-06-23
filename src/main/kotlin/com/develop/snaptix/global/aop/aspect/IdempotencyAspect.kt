package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.IdempotencyTarget
import com.develop.snaptix.global.aop.type.AspectOrder
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.redis.IdempotencyConflictException
import com.develop.snaptix.global.security.auth.AuthenticatedUser
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * @Idempotent 메서드에서 동일 사용자 + 동일 이벤트의 중복 주문을
 * Redis SET NX로 원자 차단하는 Aspect.
 *
 * 실행 순서: CB(1) → RateLimit(2) → [Idempotency(3)] → RedisLogging(4)
 *
 * 책임 범위:
 *   - 최초 락 획득 (SET NX, TTL 8분)
 *   - 인게스트 실패 시 자기 키만 정리 (compare-and-delete)
 *   - Redis 장애 시 fail-open (CB가 Order=1에서 대부분 선차단)
 *
 * 이후 생애주기는 각 레이어 담당:
 *   - ORDER_HOLD 생성(워커)    → PEXPIRE key 300_000
 *   - CONFIRMED(결제 확정)     → SET key COMPLETED KEEPTTL
 *   - CANCELLED / RELEASED    → compare-and-delete(orderId)
 *
 * compare-and-delete Lua는 RedisScriptConfig가 등록한 빈을 주입받는다(자체 컴파일 금지).
 */
@Aspect
@Component
@Order(AspectOrder.IDEMPOTENCY)
class IdempotencyAspect(
    private val redis: StringRedisTemplate,
    @Qualifier("compareAndDeleteScript")
    private val compareAndDeleteScript: RedisScript<Long>,
) {
    companion object {
        /** 인게스트 봉투 TTL: 예상 최대 큐 대기 + 홀드 5분 + 여유 (권장 8분) */
        private val ENVELOPE_TTL = Duration.ofMinutes(8)

        private val log = KotlinLogging.logger {}
    }

    @Around("@annotation(com.develop.snaptix.global.aop.annotation.Idempotent)")
    fun around(jp: ProceedingJoinPoint): Any? {
        val userId = extractUserId()
        val target = extractTarget(jp)
        val key = "idempotency:order:$userId:${target.eventId}"

        val acquired = tryAcquire(key, target.orderId, userId, target.eventId)
        if (!acquired) {
            log.warn {
                jsonLog(
                    "action" to "IDEMPOTENCY_CONFLICT",
                    "userId" to userId,
                    "eventId" to target.eventId,
                )
            }
            throw IdempotencyConflictException()
        }

        @Suppress("TooGenericExceptionCaught") // jp.proceed()는 Throwable을 선언 — AOP around에서 의도적
        return try {
            jp.proceed()
        } catch (e: Throwable) {
            // 인게스트 실패 시 자기 키만 정리 → 재시도 허용
            compareAndDelete(key, target.orderId)
            throw e
        }
    }

    // ── private helpers ────────────────────────────────────────────────────

    /**
     * Redis SET NX 시도.
     * DataAccessException 발생 시 fail-open(true 반환) — CB가 대부분 선차단하므로 안전.
     */
    private fun tryAcquire(
        key: String,
        orderId: String,
        userId: Long,
        eventId: String,
    ): Boolean = try {
        redis.opsForValue().setIfAbsent(key, orderId, ENVELOPE_TTL) ?: false
    } catch (e: DataAccessException) {
        log.warn(e) {
            jsonLog(
                "action" to "IDEMPOTENCY_CHECK",
                "result" to "SKIP_FAIL_OPEN",
                "userId" to userId,
                "eventId" to eventId,
            )
        }
        true // fail-open
    }

    /**
     * Lua compare-and-delete: 값이 orderId와 일치할 때만 DEL.
     * 타 주문이 이미 키를 재점유했다면 건드리지 않는다.
     */
    private fun compareAndDelete(
        key: String,
        orderId: String,
    ) {
        try {
            redis.execute(compareAndDeleteScript, listOf(key), orderId)
        } catch (e: DataAccessException) {
            // 키 정리 실패는 TTL 만료로 자연 해소됨 — 재시도 억제보다 유실이 더 큰 피해
            log.warn(e) {
                jsonLog(
                    "action" to "IDEMPOTENCY_CAD_FAIL",
                    "key" to key,
                )
            }
        }
    }

    /**
     * SecurityContext에서 userId(내부 PK, Long) 추출.
     */
    private fun extractUserId(): Long {
        val auth =
            SecurityContextHolder.getContext().authentication
                ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return (auth.principal as? AuthenticatedUser)?.userId
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
    }

    /**
     * JoinPoint 인자에서 IdempotencyTarget 구현체를 찾아 반환.
     * @Idempotent 메서드는 반드시 IdempotencyTarget 인자를 하나 포함해야 한다.
     */
    private fun extractTarget(jp: ProceedingJoinPoint): IdempotencyTarget = jp.args
        .filterIsInstance<IdempotencyTarget>()
        .firstOrNull()
        ?: throw IllegalArgumentException(
            "@Idempotent 메서드[${jp.signature.name}]에 IdempotencyTarget 인자가 없습니다.",
        )

    /** 구조화 로그용 간단한 JSON 직렬화 */
    private fun jsonLog(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(separator = ", ", prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\": \"$v\""
        }
}
