package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.util.UUID

/** 차감+claimed 원자 Lua의 결과. */
enum class DecreaseResult { OK, ALREADY, SOLD_OUT }

/**
 * 재고 차감(권위 관문) 및 통일 보상 게이트웨이.
 *
 * - [decreaseAndClaim]: 차감과 claimed 기록을 단일 원자 Lua로 실행 → 동시 요청에도 차감 1회.
 * - [compensate]: orderId가 claimed에 있을 때만 +1 & SREM → 이중 보상 방지.
 *
 * 모든 호출은 [ResilientRedisExecutor]로 감싸 서킷·로깅을 일괄 적용한다.
 */
@Component
class StockRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val executor: ResilientRedisExecutor,
    @Qualifier("decreaseAndClaimScript")
    private val decreaseAndClaimScript: RedisScript<String>,
    @Qualifier("compensateStockScript")
    private val compensateStockScript: RedisScript<Long>,
) {
    /**
     * 재고 차감 + claimed 기록 (권위 관문).
     * @return OK(차감 성공) / ALREADY(이미 claimed, 재차감 안 함) / SOLD_OUT(재고 0)
     */
    fun decreaseAndClaim(
        zoneId: Long,
        orderId: UUID,
    ): DecreaseResult = executor.execute(RedisAction.LUASCRIPT_DECREASE) {
        val raw =
            redis.execute(
                decreaseAndClaimScript,
                listOf(keys.stock(zoneId), keys.claimed(zoneId)),
                orderId.toString(),
            )
        DecreaseResult.valueOf(raw ?: error("decreaseAndClaim 스크립트가 null을 반환했습니다."))
    }

    /**
     * 통일 보상: orderId가 claimed에 있을 때만 재고 +1 & claimed에서 제거.
     * @return true(보상 수행) / false(claimed에 없음 → 이미 보상됐거나 차감된 적 없음)
     */
    fun compensate(
        zoneId: Long,
        orderId: UUID,
    ): Boolean = executor.execute(RedisAction.COMPENSATE_STOCK) {
        val compensated =
            redis.execute(
                compensateStockScript,
                listOf(keys.stock(zoneId), keys.claimed(zoneId)),
                orderId.toString(),
            )
        compensated == 1L
    }
}
