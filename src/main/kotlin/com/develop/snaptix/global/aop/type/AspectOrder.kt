package com.develop.snaptix.global.aop.type

object AspectOrder {
    const val RATE_LIMIT = 2 // Redis 정상일 때만 카운터 증가
    const val IDEMPOTENCY = 3 // Rate Limit 통과 후 중복 검사
    const val CACHE_ASIDE = 5
    const val ORDER_LOGGING = 6 // #14 구조화 로깅 — 도메인 레이어 최외곽
}
