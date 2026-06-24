package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisCacheGateway(
    private val redis: StringRedisTemplate,
    private val executor: ResilientRedisExecutor,
) {
    fun get(key: String): String? = executor.execute(RedisAction.CACHE_GET) {
        redis.opsForValue().get(key)
    }

    fun put(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        executor.execute(RedisAction.CACHE_SET) {
            redis.opsForValue().set(key, value, ttl)
        }
    }

    fun evict(key: String) {
        executor.execute(RedisAction.CACHE_INVALIDATE) {
            redis.delete(key)
        }
    }
}
