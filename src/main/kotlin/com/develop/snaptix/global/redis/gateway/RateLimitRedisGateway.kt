package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Rate Limit 판정 결과.
 * @property allowed 허용 여부
 * @property retryAfter 차단 시 재시도까지 권장 대기(초/분 윈도우), 허용 시 null
 */
data class RateLimitResult(
    val allowed: Boolean,
    val retryAfter: Duration?,
)

/**
 * Rate Limiting 게이트웨이.
 *
 * `rate_limit:{key}:sec`/`:min`에 원자 Lua(INCR + 최초 EXPIRE)로 슬라이딩 카운터를 둔다.
 * 한도 정책값(초당·분당)은 호출부가 전달한다(게이트웨이는 정책을 보유하지 않는다).
 *
 * `key`는 특정 스코프에 종속되지 않은 임의의 문자열이다 — IP 주소일 수도, "user:{userId}"
 * 같은 사용자 기준 키일 수도 있다(스코프 결정은 호출부 책임, `OrderIngestService` 참고).
 * 순수 IP 기준으로만 쓰면 공유 IP(NAT) 뒤에서는 오탐 429가 가능하므로, 호출부는 보통
 * 사용자 기준 한도와 IP 기준 한도를 병행 적용한다.
 */
@Component
class RateLimitRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
    @Qualifier("rateLimitScript")
    private val rateLimitScript: RedisScript<Long>,
) {
    fun hit(
        key: String,
        limitPerSecond: Int,
        limitPerMinute: Int,
    ): RateLimitResult = executor.execute(RedisAction.RATE_LIMIT_CHECK) {
        val secondCount = incrementWindow(keys.rateLimitSecond(key), ttl.rateLimitSecond)
        val minuteCount = incrementWindow(keys.rateLimitMinute(key), ttl.rateLimitMinute)
        when {
            secondCount > limitPerSecond -> RateLimitResult(false, ttl.rateLimitSecond)
            minuteCount > limitPerMinute -> RateLimitResult(false, ttl.rateLimitMinute)
            else -> RateLimitResult(true, null)
        }
    }

    /** 원자 Lua로 INCR + 최초 호출 시 EXPIRE. 현재 카운트를 반환. */
    private fun incrementWindow(
        key: String,
        window: Duration,
    ): Long = redis.execute(rateLimitScript, listOf(key), window.seconds.toString()) ?: 0L
}
