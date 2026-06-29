package com.develop.snaptix.global.realtime.subscribe

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * SSE 전용 Pub/Sub 리스너 컨테이너 (PR-05).
 *
 * 기존 RedisConnectionFactory 를 재사용한다. 컨테이너는 SmartLifecycle 로 자동 기동되며,
 * 구독은 RedisSseSubscriber 가 연결 수립/종료 시 동적으로 add/removeMessageListener 한다.
 *
 * NOTE: 프로젝트에 이미 RedisMessageListenerContainer 빈이 있으면 본 빈과 충돌할 수 있으니
 *       하나로 합치거나 @Qualifier 로 구분한다.
 */
@Configuration
class RedisSseConfig {
    @Bean
    fun sseMessageListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        return container
    }
}
