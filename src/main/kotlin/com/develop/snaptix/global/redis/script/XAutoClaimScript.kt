package com.develop.snaptix.global.redis.script

/**
 * Redis Lua — XAUTOCLAIM 실행 스크립트
 *
 * ## 왜 Lua 스크립트를 쓰는가?
 * Spring Data Redis의 `connection.execute("XAUTOCLAIM", ...)` 는 내부적으로
 * Lettuce의 `RawListOutput`을 사용하는데, 이 출력 타입은 `multi(count)` 호출이
 * 최초 1회(최상위 배열 초기화)만 동작하고 중첩 배열을 부모 리스트로 복귀시키지 않는다.
 * 결과적으로 XAUTOCLAIM의 3단 중첩 응답이 완전히 **평탄화(flatten)** 되어 파싱이 불가능하다.
 *
 * Lua 스크립트에서 `cjson.encode()`로 중첩 구조를 단일 JSON 문자열로 직렬화하면
 * Spring은 `String` 하나만 받으므로 중첩 파싱 문제를 완전히 우회할 수 있다.
 * Redis 내장 cjson은 빈 Lua 테이블을 `[]`로 인코딩하므로 빈 messages/deletedIds도 안전하다.
 *
 * ## XAUTOCLAIM 응답 구조 (RESP2 기준)
 * raw[1] = nextStartId  (string)
 * raw[2] = claimed messages array  → [[msgId, [f1, v1, f2, v2, ...]], ...]
 * raw[3] = deleted IDs array       → [delId1, delId2, ...]  (Redis 7.0+)
 *
 * ## cjson.encode 후 반환 형태
 * ["0-0", [["1234-0", ["orderId", "...", "eventId", "..."]]], []]
 *
 * KEYS[1] = stream key  (queue:order:{eventId})
 * ARGV[1] = consumer group
 * ARGV[2] = consumer name
 * ARGV[3] = min-idle-time (밀리초, 문자열)
 * ARGV[4] = start ID      (예: "0-0")
 * ARGV[5] = count         (문자열)
 * 반환값  = JSON string: [nextId, [[msgId,[f,v,...]],...], [deletedId,...]]
 */
const val XAUTOCLAIM_SCRIPT: String = """
    local raw = redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4], 'COUNT', ARGV[5])
    return cjson.encode(raw)
"""
