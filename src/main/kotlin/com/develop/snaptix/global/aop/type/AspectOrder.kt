package com.develop.snaptix.global.aop.type

object AspectOrder {
    const val CIRCUIT_BREAKER = 1 // Redis 자체가 살아있는지 먼저 확인
    const val RATE_LIMIT = 2 // Redis 정상일 때만 카운터 증가
    const val IDEMPOTENCY = 3 // Rate Limit 통과 후 중복 검사
    const val REDIS_LOGGING = 4 // 실제 연산에 가장 근접하여 정확한 시간 측정
}
