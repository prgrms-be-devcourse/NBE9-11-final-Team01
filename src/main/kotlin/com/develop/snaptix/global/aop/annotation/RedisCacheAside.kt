package com.develop.snaptix.global.aop.annotation

/**
 * Cache-Aside 패턴을 적용할 메서드에 붙이는 어노테이션.
 *
 * 캐시 키: "{keyPrefix}:{메서드 첫 번째 String 인자}"
 * 예) keyPrefix = "event:info", 인자 = publicId → "event:info:{publicId}"
 *
 * Redis 장애(DataAccessException) 시 503 없이 DB로 폴백(fail-open).
 * 무효화(DEL)는 서비스 레이어가 직접 담당한다(Aspect 외부).
 *
 * @param keyPrefix  Redis 키 접두사 (예: "event:info")
 * @param ttlSeconds 캐시 TTL(초). 기본 1시간
 * @param fallbackOnMiss Redis 장애 시 DB 폴백 여부. false 이면 예외를 다시 던진다
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedisCacheAside(
    val keyPrefix: String,
    val ttlSeconds: Long = 3600,
    val fallbackOnMiss: Boolean = true,
)
