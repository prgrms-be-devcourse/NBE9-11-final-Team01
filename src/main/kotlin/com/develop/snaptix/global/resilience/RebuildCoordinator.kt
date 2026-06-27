package com.develop.snaptix.global.resilience

import com.develop.snaptix.global.redis.gateway.RebuildLockRedisGateway
import com.develop.snaptix.global.resilience.config.ReconcileProperties
import org.springframework.stereotype.Component
import java.util.UUID

private const val REBUILD_LOCK_KEY = "rebuild:lock"

/**
 * 다중 인스턴스 단일 재구축 실행 락. (작업 명세서 v2.1 §7 · Story 13.2)
 *
 * 서킷은 **인스턴스별**이라 복구 시 여러 앱 인스턴스가 동시에 재구축을 시도할 수 있다.
 * (Redis 1대를 공유하더라도 앱 인스턴스가 N대면 in-process 단일화로는 못 막는다.)
 * 따라서 `rebuild:lock` 분산 락으로 한 인스턴스만 수행한다.
 *
 * 본 클래스는 락 키·instanceId·TTL 정책만 보유한다.
 */
@Component
class RebuildCoordinator(
    private val rebuildLockRedisGateway: RebuildLockRedisGateway,
    private val reconcileProperties: ReconcileProperties,
) {
    private val instanceId: String = UUID.randomUUID().toString()

    fun tryAcquire(): Boolean =
        rebuildLockRedisGateway.tryAcquire(REBUILD_LOCK_KEY, instanceId, reconcileProperties.rebuildLockTtl)

    fun release() {
        rebuildLockRedisGateway.release(REBUILD_LOCK_KEY, instanceId)
    }
}
