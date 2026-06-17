package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — compare-and-delete
 *
 * 키의 현재 값이 ARGV[1](orderId)과 일치할 때만 DEL.
 * 값이 다른 orderId로 교체된 경우(타 주문의 멱등 키)는 건드리지 않는다.
 *
 * KEYS[1] = 멱등 키 ("idempotency:order:{userId}:{eventId}")
 * ARGV[1] = 삭제 대상 orderId
 * 반환값   = 1 (삭제 성공) / 0 (불일치 또는 키 없음)
 */
const val COMPARE_AND_DELETE_SCRIPT: String = """
    if redis.call('GET', KEYS[1]) == ARGV[1] then
        return redis.call('DEL', KEYS[1])
    else
        return 0
    end
"""
