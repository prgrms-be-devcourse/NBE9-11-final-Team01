package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 이벤트 메타데이터 캐시(`event:info:{eventId}`) 게이트웨이. Cache-Aside, JSON String, TTL 1h.
 *
 * 저장 포맷은 **JSON String**으로 통일한다(`CacheAsideAspect` 리더와 일치). DB 폴백/무효화
 * 정책은 호출부(CacheAsideAspect·EventService) 책임이며, 본 게이트웨이는 get/put/evict 원자 연산만 제공한다.
 */
@Component
class EventCacheRedisGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val ttl: RedisTtlProperties,
    private val executor: ResilientRedisExecutor,
    private val objectMapper: ObjectMapper,
) {
    /** 캐시 조회. 미스 또는 손상(역직렬화 실패) 시 null(→ 호출부가 DB-aside). */
    fun get(eventPublicId: UUID): EventInfo? = executor.execute(RedisAction.CACHE_GET) {
        redis.opsForValue().get(keys.eventInfo(eventPublicId))?.let { json ->
            runCatching { objectMapper.readValue(json, EventInfo::class.java) }.getOrNull()
        }
    }

    /** 캐시 적재(TTL 1h). */
    fun put(
        eventPublicId: UUID,
        info: EventInfo,
    ) {
        executor.execute(RedisAction.CACHE_SET) {
            redis.opsForValue().set(keys.eventInfo(eventPublicId), objectMapper.writeValueAsString(info), ttl.eventInfo)
        }
    }

    /** 캐시 무효화(상태 변경/CLOSED). */
    fun evict(eventPublicId: UUID) {
        executor.execute(RedisAction.CACHE_INVALIDATE) {
            redis.delete(keys.eventInfo(eventPublicId))
        }
    }
}
