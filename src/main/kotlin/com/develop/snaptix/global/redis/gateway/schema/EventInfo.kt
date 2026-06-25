package com.develop.snaptix.global.redis.gateway.schema

/**
 * `event:info:{eventId}` 캐시의 정규 스키마(JSON 직렬화 대상).
 *
 * 쓰기(EventRedisInitializer / RebuildService)와 읽기(EventCacheRedisGateway / CacheAsideAspect)가
 * 동일한 이 타입·JSON 포맷을 공유해야 캐시 정합이 깨지지 않는다.
 * 필드는 기존 캐시 맵과 동일하게 모두 String(시간은 ISO-8601, status는 enum name).
 *
 * [totalCapacity] 이 이벤트에 속한 모든 zone의 총 좌석 수 합계.
 *   - 불변값(이벤트 생성 이후 변경 불가)이므로 캐시 TTL 내 별도 갱신 없이 유지된다.
 *   - null 은 기존 캐시 항목(totalCapacity 필드 없음)에서 역직렬화될 때만 발생하며,
 *     OrderIngestService 가 이를 감지해 RECONCILE_FAILED 로 처리한다.
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
    val totalCapacity: Int? = null,
)
