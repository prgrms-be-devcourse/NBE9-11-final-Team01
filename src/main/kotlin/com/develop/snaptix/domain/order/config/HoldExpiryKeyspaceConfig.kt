package com.develop.snaptix.domain.order.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * ORDER_HOLD Keyspace 이벤트 전용 리스너 컨테이너 설정.
 *
 * `order.hold.keyspace-listener.enabled=true` 일 때만 빈으로 등록된다.
 * SSE 전용 컨테이너(`sseMessageListenerContainer`)와 충돌하지 않도록
 * `keyspaceListenerContainer` 라는 별도 이름으로 등록한다.
 *
 * ## 활성화 전 체크리스트
 * - `application.yaml`: `order.hold.keyspace-listener.enabled: true`
 * - Redis 서버: `notify-keyspace-events KEX` 설정 필요
 *   (`redis.conf` 또는 `redis-cli CONFIG SET notify-keyspace-events KEX`)
 */
@Configuration
@ConditionalOnProperty(
    name = ["order.hold.keyspace-listener.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class HoldExpiryKeyspaceConfig {
    @Bean
    fun keyspaceListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        return container
    }
}
