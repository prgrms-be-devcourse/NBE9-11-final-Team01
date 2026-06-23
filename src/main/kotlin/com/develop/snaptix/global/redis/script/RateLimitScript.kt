package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — Rate Limit 카운터(INCR + 최초 호출 시 EXPIRE)
 *
 * INCR과 EXPIRE를 하나의 원자 스크립트로 묶는다. 최초 증가(count==1) 직후 크래시로
 * TTL이 누락되어 카운터가 영구히 리셋되지 않는 키 누수를 막는다(기존 RateLimitAspect와 동일).
 *
 * KEYS[1] = rate_limit:{ip}:{sec|min}
 * ARGV[1] = 윈도우 TTL(초)
 * 반환값  = 현재 카운트
 */
const val RATE_LIMIT_SCRIPT: String = """
    local count = redis.call('INCR', KEYS[1])
    if count == 1 then
        redis.call('EXPIRE', KEYS[1], ARGV[1])
    end
    return count
"""
