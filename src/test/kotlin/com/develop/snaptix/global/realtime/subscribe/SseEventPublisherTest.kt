package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

class SseEventPublisherTest {
    // convertAndSend 인자를 캡처하는 가짜 RedisTemplate
    private class CapturingRedis : StringRedisTemplate() {
        var channel: String? = null
        var payload: String? = null

        override fun convertAndSend(
            channel: String,
            message: Any,
        ): Long {
            this.channel = channel
            this.payload = message as String
            return 1L
        }
    }

    private val mapper = jacksonObjectMapper()

    @Test
    fun `publish 는 채널과 직렬화된 SseMessage 를 convertAndSend 한다`() {
        val redis = CapturingRedis()
        val publisher = SseEventPublisher(redis, mapper)

        publisher.publish(
            SseChannelKey("order", "order-1"),
            SseEvent.terminal("TICKET_ISSUED", mapOf("orderId" to "order-1")),
        )

        assertThat(redis.channel).isEqualTo("sse:order:order-1")
        val wire = mapper.readValue(redis.payload!!, SseMessage::class.java)
        assertThat(wire.name).isEqualTo("TICKET_ISSUED")
        assertThat(wire.terminal).isTrue()
    }
}
