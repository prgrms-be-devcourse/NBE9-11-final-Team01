package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * PENDING 단계(예약 행 생성 전) 주문 소유권(`order:owner:{orderId}`) 게이트웨이.
 *
 * 접수 시 `SET orderId→userId`로 소유권을 기록해 SSE 구독·상태 조회를 검증한다.
 * 행 생성 후에는 `reservations.user_id`가 권위 소스다.
 */
@Component
class OwnershipRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
) {
    /** 소유권 기록: `SET order:owner:{orderId}=userId` TTL 인게스트 봉투(8분). */
    fun set(
        orderId: UUID,
        userId: Long,
    ) {
        executor.execute(RedisAction.OWNERSHIP) {
            redis.opsForValue().set(keys.orderOwner(orderId), userId.toString(), ttl.ingestEnvelope)
        }
    }

    /** 소유자 userId 조회. 부재 또는 비정상 값이면 null. */
    fun ownerOf(orderId: UUID): Long? = executor.execute(RedisAction.OWNERSHIP) {
        redis.opsForValue().get(keys.orderOwner(orderId))?.toLongOrNull()
    }

    /** 소유권 키 정리(`XADD` 실패/이벤트 종료 시). */
    fun delete(orderId: UUID) {
        executor.execute(RedisAction.OWNERSHIP) {
            redis.delete(keys.orderOwner(orderId))
        }
    }
}
