package com.develop.snaptix.global.realtime.subscribe

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * SSE 전용 Pub/Sub 리스너 컨테이너 (PR-05).
 *
 * 기존 RedisConnectionFactory 를 재사용한다. 컨테이너는 SmartLifecycle 로 자동 기동되며,
 * 구독은 RedisSseSubscriber 가 연결 수립/종료 시 동적으로 add/removeMessageListener 한다.
 *
 * NOTE: 프로젝트에 이미 RedisMessageListenerContainer 빈이 있으면 본 빈과 충돌할 수 있으니
 *       하나로 합치거나 @Qualifier 로 구분한다.
 *
 * ## Keepalive 구독 (RedisInvalidSubscriptionException 회피)
 * `RedisMessageListenerContainer`는 구독 채널 수가 0 → 1 로 늘어날 때 내부 네이티브 Redis
 * 구독(Subscription)을 새로 만들고, 1 → 0 으로 줄어들 때 그 Subscription 을 완전히
 * 종료(unsubscribe)한다. `RedisSseSubscriber`는 주문마다 채널(`sse:order:{orderId}`)을
 * 동적으로 add/removeMessageListener 하는데, 여러 주문이 동시에 SSE connect/disconnect를
 * 반복하면 "현재 활성 채널 수가 순간적으로 0이 됐다가 곧바로 다시 늘어나는" 전이 구간이
 * 자주 발생한다. 이 구간에서 컨테이너가 방금 종료한 Subscription 객체를 다른 스레드가
 * 재사용하려다 `RedisInvalidSubscriptionException: Subscription has been unsubscribed and
 * cannot be used anymore`이 발생한다(부하 테스트에서 다수 확인됨 — 이슈 #362 관련 서브 이슈).
 *
 * 해결책: 앱 기동 시 실제 주문 채널과 절대 겹치지 않는 keepalive 채널을 하나 고정 구독해
 * 두고 절대 해제하지 않는다. 컨테이너 관점에서 "활성 채널 수"가 0으로 떨어지는 일이
 * 없어지므로, 주문 채널들의 add/remove는 항상 이미 살아있는 Subscription 위에서만
 * 일어나 위 race(전체 종료 후 재생성)가 원천적으로 발생하지 않는다.
 * (아무도 이 채널에 publish 하지 않으므로 keepalive 리스너는 실제로 호출되지 않는다.)
 */
@Configuration
class RedisSseConfig {
    @Bean
    fun sseMessageListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)

        // Keepalive: 컨테이너가 절대 완전히 unsubscribe 되지 않도록 더미 채널을 고정 구독.
        // "sse:" 프리픽스를 쓰지 않아 SseMessageListener.parseChannel()이 파싱 대상으로도
        // 인식하지 않고(= 혹시 호출돼도 완전 no-op), SseChannelKey.redisChannel()이 만드는
        // 실제 채널명("sse:{resource}:{id}")과도 절대 충돌하지 않는다.
        // 컨테이너가 아직 시작(SmartLifecycle#start) 되기 전이므로 여기서는 실제 구독을
        // 걸지 않고 큐잉만 되며, 컨테이너 시작 시 다른 리스너들과 함께 정상 등록된다.
        container.addMessageListener(
            MessageListener { _, _ -> },
            ChannelTopic(KEEPALIVE_CHANNEL),
        )

        return container
    }

    companion object {
        const val KEEPALIVE_CHANNEL = "sse-container:keepalive"
    }
}
