package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — 재고 차감 + claimed 기록 (원자, 권위 관문)
 *
 * 차감과 claimed 기록을 하나의 원자 스크립트로 실행한다. Redis 단일 스레드·스크립트
 * 원자성 덕분에 동시 요청이 와도 차감은 정확히 1회다. (Story 3.1)
 *
 * KEYS[1] = ZONE:{zoneId}:stock
 * KEYS[2] = ZONE:{zoneId}:claimed
 * ARGV[1] = orderId
 * 반환값  =
 *   "OK"       차감 성공(stock-1, claimed에 orderId 추가)
 *   "ALREADY"  이미 claimed → 재차감하지 않음(재배달·동시 처리). 실패가 아니라 "이미 성공"
 *   "SOLD_OUT" 재고 0 이하(차감 없음)
 *
 * 'ALREADY'는 성공 차감 후에만 set에 들어가므로 SOLD_OUT과 섞이지 않는다.
 */
const val DECREASE_AND_CLAIM_SCRIPT: String = """
    if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
        return 'ALREADY'
    end
    local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
    if stock <= 0 then
        return 'SOLD_OUT'
    end
    redis.call('DECR', KEYS[1])
    redis.call('SADD', KEYS[2], ARGV[1])
    return 'OK'
"""
