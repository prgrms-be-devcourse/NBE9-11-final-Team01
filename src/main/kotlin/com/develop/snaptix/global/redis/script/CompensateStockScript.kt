package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — 통일 보상(+1 & SREM), 이중 보상 방지
 *
 * orderId가 claimed에 있을 때만 재고를 1 복구하고 claimed에서 제거한다.
 * 멤버십 가드 덕분에 같은 orderId로 두 번 호출돼도 +1은 한 번만 일어난다. (Story 3.2)
 *
 * KEYS[1] = ZONE:{zoneId}:stock
 * KEYS[2] = ZONE:{zoneId}:claimed
 * ARGV[1] = orderId
 * 반환값  = 1 (보상 수행) / 0 (claimed에 없음 → 이미 보상됐거나 차감된 적 없음)
 */
const val COMPENSATE_STOCK_SCRIPT: String = """
    if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
        return 0
    end
    redis.call('INCR', KEYS[1])
    redis.call('SREM', KEYS[2], ARGV[1])
    return 1
"""
