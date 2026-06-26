package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 재구축 단일 실행 락 게이트웨이. (작업 명세서 v2.1 §7)
 *  - tryAcquire : `SET key token NX EX ttl` (보유자만 true)
 *  - release    : `@Qualifier("compareAndDeleteScript")` 빈으로 **값 일치 시에만** DEL(자기 락만 해제)
 */
@Component
class RebuildLockRedisGateway(
    private val redis: StringRedisTemplate,
    private val executor: ResilientRedisExecutor,
    @Qualifier("compareAndDeleteScript")
    private val compareAndDeleteScript: RedisScript<Long>,
) {
    fun tryAcquire(
        key: String,
        token: String,
        ttl: Duration,
    ): Boolean = executor.execute(RedisAction.REBUILD_LOCK) {
        // redis 분산락 -> setIfAbsent(지정된 키가 존재하지 않을 경우만 값을 설정)
        redis.opsForValue().setIfAbsent(key, token, ttl) ?: false
    }

    fun release(
        key: String,
        token: String,
    ) {
        executor.execute(RedisAction.REBUILD_LOCK) {
            // GET == token 일 때만 DEL → 만료 후 타 인스턴스가 잡은 락 보호
            redis.execute(compareAndDeleteScript, listOf(key), token)
        }
    }
}
