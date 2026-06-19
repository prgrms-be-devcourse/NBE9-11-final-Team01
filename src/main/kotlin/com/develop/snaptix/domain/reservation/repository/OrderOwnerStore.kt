package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.global.aop.annotation.RedisCircuitBreaker
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

/**
 * PENDING(예약 행 생성 전) 단계의 주문 소유권 조회 포트.
 * `order:owner:{orderId}` 값(=userId)을 읽는다. (ERD Redis Key 명세, Story 2.1)
 *
 * `order:owner` 는 주문 인게스트(POST /orders) 시 기록되는 키이므로 reservation 도메인 소관.
 */
fun interface OrderOwnerStore {
    /** @return 소유자 userId, 키 없으면 null */
    fun findOwnerUserId(orderId: String): Long?
}

/**
 * Redis 기반 구현. 기존 패턴대로 [StringRedisTemplate] 직접 접근 + [RedisCircuitBreaker]로 보호한다.
 * (CB OPEN 시 RedisUnavailableException → 503. 다른 빈에서 호출되므로 AOP 발동)
 */
@Repository
class RedisOrderOwnerStore(
    private val redis: StringRedisTemplate,
) : OrderOwnerStore {
    @RedisCircuitBreaker
    override fun findOwnerUserId(orderId: String): Long? {
        val ownerId = redis.opsForValue().get("$OWNER_KEY_PREFIX$orderId")
        return ownerId?.toLongOrNull()
    }

    companion object {
        private const val OWNER_KEY_PREFIX = "order:owner:"
    }
}
