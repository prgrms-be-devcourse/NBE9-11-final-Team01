package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.port.SseChannelSubscriber
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * [SseChannelSubscriber]의 Redis Pub/Sub 구현 (PR-05).
 *
 * 각 인스턴스가 자신에게 연결된 채널만 동적 구독/해제한다(패턴 구독 미사용).
 * 이 빈이 등록되면 InMemorySseConnectionManager 의 NoOp 기본 구독자를 자동 대체한다.
 */
@Component
class RedisSseSubscriber(
    // SSE 전용 컨테이너를 명시(자동 설정된 redisMessageListenerContainer 와 구분)
    @Qualifier("sseMessageListenerContainer")
    private val container: RedisMessageListenerContainer,
    private val listener: SseMessageListener,
) : SseChannelSubscriber {
    /** 현재 구독 중인 채널(중복 구독 방지). */
    private val topics = ConcurrentHashMap.newKeySet<String>()

    override fun subscribe(key: SseChannelKey) {
        val channel = key.redisChannel()
        if (topics.add(channel)) {
            container.addMessageListener(listener, ChannelTopic(channel))
        }
    }

    override fun unsubscribe(key: SseChannelKey) {
        val channel = key.redisChannel()
        if (topics.remove(channel)) {
            container.removeMessageListener(listener, ChannelTopic(channel))
        }
    }
}
