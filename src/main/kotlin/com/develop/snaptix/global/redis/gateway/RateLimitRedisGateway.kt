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
 * IP 기반 Rate Limiting 게이트웨이.
 *
 * `rate_limit:{ip}:sec`/`:min`에 원자 Lua(INCR + 최초 EXPIRE)로 슬라이딩 카운터를 둔다.
 * 한도 정책값(초당·분당)은 호출부가 전달한다(게이트웨이는 정책을 보유하지 않는다).
 * 한계: IP 단위라 공유 IP(NAT) 뒤에서는 오탐 429 가능(MVP 허용).
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
        ip: String,
        limitPerSecond: Int,
        limitPerMinute: Int,
    ): RateLimitResult = executor.execute(RedisAction.RATE_LIMIT_CHECK) {
        val secondCount = incrementWindow(keys.rateLimitSecond(ip), ttl.rateLimitSecond)
        val minuteCount = incrementWindow(keys.rateLimitMinute(ip), ttl.rateLimitMinute)
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
