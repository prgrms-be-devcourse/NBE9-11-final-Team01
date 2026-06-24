// 위치: src/main/kotlin/com/develop/snaptix/global/redis/gateway/StockRedisGateway.kt
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
 * 재고 차감(권위 관문)·보상·조회·정산·재구축 게이트웨이.
 *
 * - [decreaseAndClaim]: 차감과 claimed 기록을 단일 원자 Lua로 → 동시 요청에도 차감 1회.
 * - [compensate]: orderId가 claimed에 있을 때만 +1 & SREM → 이중 보상 방지.
 * - [releaseClaim]: 성공 확정 시 stock은 유지하고 claimed만 제거.
 * - [get]: 현재 재고 조회(드리프트 점검). 키 부재 시 null.
 * - [correctStock]: 드리프트 누수 절대 SET(stock만, claimed 미접촉).
 * - [rebuild]: 상태 재구축(stock SET + claimed 원자 덮어쓰기, Story 13.2).
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
    @Qualifier("rebuildZoneScript")
    private val rebuildZoneScript: RedisScript<Long>,
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

    /**
     * 성공 확정: stock은 유지하고 claimed에서 orderId만 제거한다.
     * @return true(제거됨) / false(claimed에 없음)
     */
    fun releaseClaim(
        zoneId: Long,
        orderId: UUID,
    ): Boolean = executor.execute(RedisAction.CLAIM_RELEASE) {
        redis.opsForSet().remove(keys.claimed(zoneId), orderId.toString()) == 1L
    }

    /** 현재 재고. 키 부재 시 null(드리프트 조회용). */
    fun get(zoneId: Long): Int? = executor.execute(RedisAction.STOCK_GET) {
        redis.opsForValue().get(keys.stock(zoneId))?.toIntOrNull()
    }

    /** 현재 재고 일괄 조회. 키 부재 또는 숫자 파싱 실패 시 해당 zoneId 값은 null. */
    fun getAll(zoneIds: List<Long>): Map<Long, Int?> = executor.execute(RedisAction.STOCK_GET) {
        if (zoneIds.isEmpty()) {
            return@execute emptyMap()
        }

        val stockKeys = zoneIds.map(keys::stock)
        val values = redis.opsForValue().multiGet(stockKeys).orEmpty()

        zoneIds
            .zip(values)
            .associate { (zoneId, value) -> zoneId to value?.toIntOrNull() }
    }

    /**
     * 드리프트 누수 보정 — stock만 절대 SET. claimed는 절대 건드리지 않는다.
     * 방향(누수만/오버셀 알림만) 판단은 호출부(DriftReconciliationService) 책임.
     */
    fun correctStock(
        zoneId: Long,
        expected: Int,
    ) {
        executor.execute(RedisAction.STOCK_DRIFT_FIX) {
            redis.opsForValue().set(keys.stock(zoneId), expected.toString())
        }
    }

    /**
     * 상태 재구축 — stock SET + claimed 원자 덮어쓰기(DEL 후 재구성). 직후 +1 금지(Story 13.2).
     * 원자 Lua로 실행해 중간 상태 관측을 차단한다.
     */
    fun rebuild(
        zoneId: Long,
        stock: Int,
        claimedOrderIds: Collection<UUID>,
    ) {
        executor.execute(RedisAction.STOCK_REBUILD) {
            val args: Array<Any> =
                buildList<Any> {
                    add(stock.toString())
                    claimedOrderIds.forEach { add(it.toString()) }
                }.toTypedArray()
            redis.execute(
                rebuildZoneScript,
                listOf(keys.stock(zoneId), keys.claimed(zoneId)),
                *args,
            )
        }
    }
}
