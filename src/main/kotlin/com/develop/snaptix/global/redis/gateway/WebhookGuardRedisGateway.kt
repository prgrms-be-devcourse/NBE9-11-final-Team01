package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Webhook 멱등 가드(`webhook:processed:{orderId}`).
 *
 * **처리 성공 후** `SET NX`로 등록한다(처리 전 등록 시 중간 실패가 영구 미처리되므로 금지).
 * 키가 소실돼도 조건부 UPDATE가 멱등 backstop이다(Story 8-2).
 */
@Component
class WebhookGuardRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
) {
    /** 처리 완료 여부 조회. true면 Webhook 재전송을 빠르게 스킵한다. */
    fun isProcessed(orderId: UUID): Boolean = executor.execute(RedisAction.WEBHOOK_IDEMPOTENCY) {
        redis.hasKey(keys.webhookProcessed(orderId)) ?: false
    }

    /**
     * 처리 완료 멱등 등록.
     * @return true(첫 등록) / false(이미 처리됨 → 스킵)
     */
    fun markProcessed(orderId: UUID): Boolean = executor.execute(RedisAction.WEBHOOK_IDEMPOTENCY) {
        redis
            .opsForValue()
            .setIfAbsent(keys.webhookProcessed(orderId), MARKER, ttl.webhookProcessed)
            ?: false
    }

    companion object {
        private const val MARKER = "1"
    }
}
