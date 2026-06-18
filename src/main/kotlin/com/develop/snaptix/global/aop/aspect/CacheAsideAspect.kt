package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RedisCacheAside
import com.develop.snaptix.global.aop.type.AspectOrder
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * @RedisCacheAside 메서드에 Cache-Aside 패턴을 적용하는 Aspect.
 *
 * ┌ Redis GET ──────────────────────────────────────────────────────────────┐
 * │  HIT      → 역직렬화 후 반환 (DB 미조회)              [CACHE_GET HIT]   │
 * │  MISS     → proceed(DB) → SET best-effort → 반환      [CACHE_GET MISS]  │
 * │  손상     → DEL 후 proceed(DB) 반환                   [CACHE_GET CORRUPTED] │
 * │  장애     → fallbackOnMiss=true 이면 proceed(DB) 반환 [CACHE_GET FALLBACK_DB] │
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * 실행 순서: 주문 체인(CB→RateLimit→Idempotency→Logging)과 별개로
 *            조회 메서드만 감싸므로 @Order(5)로 독립 위치시킨다.
 *
 * 무효화(DEL)는 이 Aspect 범위 밖 — EventService 레이어가 직접 담당:
 *   - 등록(A-01): SET event:info:{publicId} (캐시 선발급)
 *   - 상태변경(A-02)/CLOSED: DEL event:info:{publicId}
 *   - Redis 복구(Story 13.2): RebuildService가 SET
 */
@Aspect
@Component
@Order(AspectOrder.CACHE_ASIDE)
class CacheAsideAspect(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Around("@annotation(cacheAside)")
    fun around(
        jp: ProceedingJoinPoint,
        cacheAside: RedisCacheAside,
    ): Any? {
        val key = buildKey(jp, cacheAside)
        val start = System.currentTimeMillis()
        val traceId = MDC.get("traceId") ?: "unknown"

        return when (val result = tryGetCached(key, cacheAside, traceId, start)) {
            is GetResult.Hit -> resolveHit(result.json, jp, key, traceId, start)
            GetResult.Miss -> resolveMiss(jp, cacheAside, key, traceId, start)
            GetResult.Fallback -> jp.proceed()
        }
    }

    // ── Redis GET ─────────────────────────────────────────────────────

    private fun tryGetCached(
        key: String,
        cacheAside: RedisCacheAside,
        traceId: String,
        start: Long,
    ): GetResult =
        try {
            when (val json = redis.opsForValue().get(key)) {
                null -> GetResult.Miss
                else -> GetResult.Hit(json)
            }
        } catch (e: DataAccessException) {
            if (!cacheAside.fallbackOnMiss) throw e
            logWarn("CACHE_GET", "FALLBACK_DB", key, traceId, elapsed(start), e)
            GetResult.Fallback
        }

    // ── HIT 처리 ──────────────────────────────────────────────────────

    private fun resolveHit(
        json: String,
        jp: ProceedingJoinPoint,
        key: String,
        traceId: String,
        start: Long,
    ): Any? {
        val returnType = (jp.signature as MethodSignature).returnType
        return try {
            objectMapper
                .readValue(json, returnType)
                .also { logInfo("CACHE_GET", "HIT", key, traceId, elapsed(start)) }
        } catch (e: JacksonException) {
            logWarn("CACHE_GET", "CORRUPTED", key, traceId, elapsed(start), e)
            evictCorrupted(key, traceId, start)
            jp.proceed()
        }
    }

    // ── MISS 처리 ─────────────────────────────────────────────────────

    private fun resolveMiss(
        jp: ProceedingJoinPoint,
        cacheAside: RedisCacheAside,
        key: String,
        traceId: String,
        start: Long,
    ): Any? {
        val result = jp.proceed()
        logInfo("CACHE_GET", "MISS", key, traceId, elapsed(start))
        if (result != null) setCache(key, result, cacheAside.ttlSeconds, traceId, start)
        return result
    }

    // ── 캐시 적재 (best-effort) ───────────────────────────────────────

    private fun setCache(
        key: String,
        value: Any,
        ttlSeconds: Long,
        traceId: String,
        start: Long,
    ) {
        try {
            val json = objectMapper.writeValueAsString(value)
            redis.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds))
            logInfo("CACHE_SET", "SUCCESS", key, traceId, elapsed(start))
        } catch (e: JacksonException) {
            logWarn("CACHE_SET", "FAIL", key, traceId, elapsed(start), e)
        } catch (e: DataAccessException) {
            logWarn("CACHE_SET", "FAIL", key, traceId, elapsed(start), e)
        }
    }

    // ── 손상 키 제거 ──────────────────────────────────────────────────

    private fun evictCorrupted(
        key: String,
        traceId: String,
        start: Long,
    ) {
        try {
            redis.delete(key)
        } catch (e: DataAccessException) {
            // 삭제 실패는 TTL 만료로 자연 해소 — 진행은 계속
            logWarn("CACHE_GET", "DEL_FAIL", key, traceId, elapsed(start), e)
        }
    }

    // ── 키 구성 ───────────────────────────────────────────────────────

    /**
     * 캐시 키 구성: "{keyPrefix}:{첫 번째 String 인자}"
     * 예) getEventInfo(publicId: String) → "event:info:{publicId}"
     */
    private fun buildKey(
        jp: ProceedingJoinPoint,
        cacheAside: RedisCacheAside,
    ): String {
        val id =
            jp.args
                .filterIsInstance<String>()
                .firstOrNull()
                ?: throw IllegalArgumentException(
                    "@RedisCacheAside 메서드[${jp.signature.name}]에 String 식별자 인자가 없습니다.",
                )
        return "${cacheAside.keyPrefix}:$id"
    }

    // ── 로깅 ─────────────────────────────────────────────────────────

    private fun elapsed(start: Long) = System.currentTimeMillis() - start

    private fun logInfo(
        action: String,
        result: String,
        key: String,
        traceId: String,
        ms: Long,
    ) {
        log.atInfo {
            message = "Cache operation"
            payload =
                mapOf(
                    "action" to action,
                    "result" to result,
                    "key" to key,
                    "traceId" to traceId,
                    "executionTimeMs" to ms,
                )
        }
    }

    private fun logWarn(
        action: String,
        result: String,
        key: String,
        traceId: String,
        ms: Long,
        cause: Throwable? = null,
    ) {
        log.atWarn {
            message = "Cache operation warning"
            this.cause = cause
            payload =
                mapOf(
                    "action" to action,
                    "result" to result,
                    "key" to key,
                    "traceId" to traceId,
                    "executionTimeMs" to ms,
                )
        }
    }

    // ── 캐시 조회 결과 타입 ───────────────────────────────────────────

    private sealed interface GetResult {
        data class Hit(
            val json: String,
        ) : GetResult

        data object Miss : GetResult

        data object Fallback : GetResult
    }
}
