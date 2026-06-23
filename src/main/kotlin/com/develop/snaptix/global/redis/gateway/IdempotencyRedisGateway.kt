package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 멱등 키(`idempotency:order:{userId}:{eventId}`) 정책 게이트웨이.
 *
 * - [tryAcquire]: 값=orderId로 `SET NX PX`(인게스트 봉투 TTL) 원자 선점.
 * - [reanchor]: 워커 홀드 생성 시 TTL을 홀드(5분)로 재설정.
 * - [compareAndDelete]: 값이 그 orderId일 때만 DEL → 유저 단위 키 경합(타임아웃↔재시도) 제거.
 *
 * `markCompleted`(SET COMPLETED KEEPTTL)는 소비처(ISSUE-14)에서 KEEPTTL 스크립트와 함께 추가한다.
 * 모든 호출은 [ResilientRedisExecutor](action `IDEMPOTENCY_CHECK`)로 감싼다.
 */
@Component
class IdempotencyRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
    @Qualifier("compareAndDeleteScript")
    private val compareAndDeleteScript: RedisScript<Long>,
) {
    /**
     * 멱등 키를 값=orderId로 원자 선점(`SET NX PX`).
     * @return true(선점 성공) / false(이미 존재 → 중복)
     */
    fun tryAcquire(
        userId: Long,
        eventId: UUID,
        orderId: UUID,
    ): Boolean = executor.execute(RedisAction.IDEMPOTENCY_CHECK) {
        redis
            .opsForValue()
            .setIfAbsent(keys.idempotency(userId, eventId), orderId.toString(), ttl.ingestEnvelope)
            ?: false
    }

    /** 워커 홀드 생성 시 멱등 키 TTL을 홀드(5분)로 재설정(`PEXPIRE`). */
    fun reanchor(
        userId: Long,
        eventId: UUID,
    ) {
        executor.execute(RedisAction.IDEMPOTENCY_CHECK) {
            redis.expire(keys.idempotency(userId, eventId), ttl.orderHold)
        }
    }

    /**
     * 값이 그 orderId와 일치할 때만 삭제(compare-and-delete).
     * @return true(삭제됨) / false(값 불일치 또는 키 없음)
     */
    fun compareAndDelete(
        userId: Long,
        eventId: UUID,
        orderId: UUID,
    ): Boolean = executor.execute(RedisAction.IDEMPOTENCY_CHECK) {
        redis.execute(
            compareAndDeleteScript,
            listOf(keys.idempotency(userId, eventId)),
            orderId.toString(),
        ) == 1L
    }
}
