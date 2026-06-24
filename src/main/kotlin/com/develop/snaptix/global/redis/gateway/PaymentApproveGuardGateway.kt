package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 결제 승인 이중 클릭 가드(`payment:approve:{orderId}`).
 *
 * `POST /payments/mock/approve` 시 `SET NX`(TTL 60초). 이미 존재하면 이중 클릭으로 간주해
 * Mock PG 중복 호출을 차단한다(Story 8-3).
 */
@Component
class PaymentApproveGuardGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
) {
    /**
     * 결제 승인 시도.
     * @return true(승인 진행 가능) / false(이중 클릭 — 차단)
     */
    fun tryApprove(orderId: UUID): Boolean = executor.execute(RedisAction.PAYMENT_APPROVE) {
        redis
            .opsForValue()
            .setIfAbsent(keys.paymentApprove(orderId), MARKER, ttl.paymentApprove)
            ?: false
    }

    companion object {
        private const val MARKER = "1"
    }
}
