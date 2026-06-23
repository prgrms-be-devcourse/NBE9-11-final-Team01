package com.develop.snaptix.global.redis.gateway.schema

/**
 * `event:info:{eventId}` 캐시의 정규 스키마(JSON 직렬화 대상).
 *
 * 쓰기(EventRedisInitializer / RebuildService)와 읽기(EventCacheRedisGateway / CacheAsideAspect)가
 * 동일한 이 타입·JSON 포맷을 공유해야 캐시 정합이 깨지지 않는다.
 * 필드는 기존 캐시 맵과 동일하게 모두 String(시간은 ISO-8601, status는 enum name).
 */
data class EventInfo(
    val eventId: String,
    val name: String,
    val description: String,
    val location: String,
    val startTime: String,
    val endTime: String,
    val status: String,
    val posterUrl: String,
)
