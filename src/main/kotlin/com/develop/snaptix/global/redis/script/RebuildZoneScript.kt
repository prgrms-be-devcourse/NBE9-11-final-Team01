package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — zone 재구축(stock SET + claimed 원자 덮어쓰기)
 *
 * stock SET, claimed DEL, claimed SADD(가변 멤버)를 하나의 원자 스크립트로 묶는다.
 * 분리 명령 시 중간 상태(stock 갱신·claimed 비어있음) 관측을 차단한다(Story 13.2).
 * 직후 별도 `+1` 보정은 하지 않는다(SET이 이미 정확).
 *
 * KEYS[1] = ZONE:{zoneId}:stock
 * KEYS[2] = ZONE:{zoneId}:claimed
 * ARGV[1] = 재산정 stock 값
 * ARGV[2..] = 유효 PENDING orderId 목록(없으면 비어 SADD 미실행)
 * 반환값  = 1
 */
const val REBUILD_ZONE_SCRIPT: String = """
    redis.call('SET', KEYS[1], ARGV[1])
    redis.call('DEL', KEYS[2])
    for i = 2, #ARGV do
        redis.call('SADD', KEYS[2], ARGV[i])
    end
    return 1
"""
