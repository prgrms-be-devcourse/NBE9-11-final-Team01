package com.develop.snaptix.global.realtime.port

import com.develop.snaptix.global.realtime.SseChannelKey

/**
 * 채널 구독/해제 포트.
 *
 * 다중 서버 브로드캐스팅을 위해 각 인스턴스가 자신에게 연결된 채널만 Redis Pub/Sub으로
 * 동적 구독한다. 실제 구현은 PR-05(RedisSseSubscriber).
 *
 * 이 포트를 계약(PR-01)에 포함시켜, 정리 콜백(PR-04)이 구독 구현(PR-05)보다 먼저
 * 머지될 수 있게 한다. PR-05 머지 전까지는 [NoOpSseChannelSubscriber]가 기본 바인딩된다.
 */
interface SseChannelSubscriber {
    fun subscribe(key: SseChannelKey)

    fun unsubscribe(key: SseChannelKey)
}

/**
 * 기본 no-op 구현. PR-05의 RedisSseSubscriber 빈이 없을 때 바인딩되어,
 * 단일 인스턴스 환경/초기 단계에서도 컴파일·동작이 가능하게 한다.
 *
 * 권장 와이어링(PR-02 또는 global/config):
 *   @Bean @ConditionalOnMissingBean(SseChannelSubscriber::class)
 *   fun noOpSseChannelSubscriber() = NoOpSseChannelSubscriber()
 */
class NoOpSseChannelSubscriber : SseChannelSubscriber {
    override fun subscribe(key: SseChannelKey) = Unit

    override fun unsubscribe(key: SseChannelKey) = Unit
}
