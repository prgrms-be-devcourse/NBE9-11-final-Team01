package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 결제 홀드(`ORDER_HOLD:{orderId}`) 게이트웨이.
 *
 * 생성 시 TTL(5분)로 결제 타임아웃을 건다. [exists]는 보조 신호일 뿐이며,
 * "결제 가능" 판정은 `reservation.status = PENDING_PAYMENT` + 홀드 윈도우로 한다(Story 8-3).
 */
@Component
class OrderHoldRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
) {
    /** 홀드 생성: `SET ORDER_HOLD:{orderId}` TTL 5분. */
    fun create(orderId: UUID) {
        executor.execute(RedisAction.HOLD_CREATE) {
            redis.opsForValue().set(keys.orderHold(orderId), HOLD_MARKER, ttl.orderHold)
        }
    }

    /**
     * 홀드 존재 여부(보조 신호). 결제 가능 판정의 단독 근거로 쓰지 않는다.
     * advisory read이므로 executor를 거치지 않는다(키 소실돼도 status+윈도우로 판정).
     */
    fun exists(orderId: UUID): Boolean = redis.hasKey(keys.orderHold(orderId)) ?: false

    /** 홀드 삭제(결제 성공/실패/타임아웃). 멱등(없어도 무해). */
    fun delete(orderId: UUID) {
        executor.execute(RedisAction.HOLD_RELEASE) {
            redis.delete(keys.orderHold(orderId))
        }
    }

    companion object {
        private const val HOLD_MARKER = "1"
    }
}
