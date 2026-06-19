package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * SSE 이벤트 발행 헬퍼 (PR-05). 워커(또는 모든 발행자)가 사용한다.
 * 「SSE 발행 계약 명세서 §4」 — `sse:{resource}:{id}` 채널로 [SseMessage] JSON 을 PUBLISH.
 *
 * 발행은 best-effort 통지다. 유실돼도 결제 정합성은 MySQL 이 보장하며,
 * 통지 유실은 재연결 재구성(Story 10.1-B)이 backstop 한다.
 */
@Component
class SseEventPublisher(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun publish(
        key: SseChannelKey,
        event: SseEvent,
    ) {
        val payload = objectMapper.writeValueAsString(SseMessage(event.name, event.data, event.terminal))
        redis.convertAndSend(key.redisChannel(), payload)
    }
}
