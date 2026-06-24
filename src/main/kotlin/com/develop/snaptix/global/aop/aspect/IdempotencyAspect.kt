// 위치: src/main/kotlin/com/develop/snaptix/global/aop/aspect/IdempotencyAspect.kt
package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.IdempotencyTarget
import com.develop.snaptix.global.aop.type.AspectOrder
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.redis.IdempotencyConflictException
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.security.auth.AuthenticatedUser
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * @Idempotent 메서드에서 동일 사용자 + 동일 이벤트의 중복 주문을 원자 차단하는 Aspect.
 *
 * 실행 순서: CB(1) → RateLimit(2) → [Idempotency(3)] → RedisLogging(4)
 *
 * 책임 범위:
 *   - 최초 락 획득 / 인게스트 실패 시 자기 키만 정리 / Redis 장애 시 fail-open
 *
 * 실제 Redis 연산(SET NX·compare-and-delete·TTL)은 [IdempotencyRedisGateway]에 위임한다
 * (메인 명세서 §4.5 — 아스펙트는 경계·정책, 게이트웨이는 Redis 연산·서킷·로깅).
 * 이후 생애주기(워커 재앵커링·CONFIRMED COMPLETED·CANCELLED/RELEASED CAD)는 각 레이어가 게이트웨이로 직접 수행.
 */
@Aspect
@Component
@Order(AspectOrder.IDEMPOTENCY)
class IdempotencyAspect(
    private val idempotencyGateway: IdempotencyRedisGateway,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Around("@annotation(com.develop.snaptix.global.aop.annotation.Idempotent)")
    fun around(jp: ProceedingJoinPoint): Any? {
        val userId = extractUserId()
        val target = extractTarget(jp)
        val eventId = UUID.fromString(target.eventId)
        val orderId = UUID.fromString(target.orderId)

        if (!tryAcquire(userId, eventId, orderId)) {
            log.warn {
                jsonLog(
                    "action" to "IDEMPOTENCY_CONFLICT",
                    "userId" to userId,
                    "eventId" to eventId,
                )
            }
            throw IdempotencyConflictException()
        }

        @Suppress("TooGenericExceptionCaught") // jp.proceed()는 Throwable을 선언 — AOP around에서 의도적
        return try {
            jp.proceed()
        } catch (e: Throwable) {
            // 인게스트 실패 시 자기 키만 정리 → 재시도 허용
            cleanup(userId, eventId, orderId)
            throw e
        }
    }

    // ── private helpers ────────────────────────────────────────────────────

    /**
     * 멱등 키 선점(게이트웨이 위임).
     * DataAccessException 발생 시 fail-open(true) — 서킷 OPEN은 상위 CB 아스펙트가 선차단한다.
     */
    private fun tryAcquire(
        userId: Long,
        eventId: UUID,
        orderId: UUID,
    ): Boolean = try {
        idempotencyGateway.tryAcquire(userId, eventId, orderId)
    } catch (e: DataAccessException) {
        log.warn(e) {
            jsonLog(
                "action" to "IDEMPOTENCY_CHECK",
                "result" to "SKIP_FAIL_OPEN",
                "userId" to userId,
                "eventId" to eventId,
            )
        }
        true
    }

    /**
     * 값이 자기 orderId일 때만 삭제(게이트웨이 위임). 정리 실패는 TTL 만료로 자연 해소.
     */
    private fun cleanup(
        userId: Long,
        eventId: UUID,
        orderId: UUID,
    ) {
        try {
            idempotencyGateway.compareAndDelete(userId, eventId, orderId)
        } catch (e: DataAccessException) {
            log.warn(e) {
                jsonLog(
                    "action" to "IDEMPOTENCY_CAD_FAIL",
                    "userId" to userId,
                    "eventId" to eventId,
                )
            }
        }
    }

    /** SecurityContext에서 userId(내부 PK, Long) 추출. */
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
