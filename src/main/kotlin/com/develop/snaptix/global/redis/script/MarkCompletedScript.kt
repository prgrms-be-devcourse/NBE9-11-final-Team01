package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — 멱등 키 완료 마킹 (`SET key COMPLETED KEEPTTL`).
 *
 * CONFIRMED 결제 확정 시, 멱등 키 값을 "COMPLETED"로 갱신하되 기존 TTL을 그대로 유지한다.
 * `SET key value KEEPTTL`은 Redis 6.0+ 명령이며 Spring Data Redis `opsForValue().set()`이
 * 직접 지원하지 않으므로 Lua로 처리한다.
 *
 * ### 동작
 * - 키가 없으면(이미 만료) → NOOP, 0 반환
 *   (재구매 방지는 DB `uk_active_user_event` 제약이 최종 방어선)
 * - 키가 있으면 → `SET key COMPLETED KEEPTTL`, 1 반환
 *
 * ### KEYS / ARGV
 * - KEYS[1] = `idempotency:order:{userId}:{eventPublicId}`
 * - ARGV[1] = "COMPLETED"
 *
 * ### 반환값
 * - `1L` : 갱신 완료
 * - `0L` : 키 없음 (만료) → no-op
 */
const val MARK_COMPLETED_SCRIPT: String = """
    if redis.call('EXISTS', KEYS[1]) == 0 then
        return 0
    end
    redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
    return 1
"""
