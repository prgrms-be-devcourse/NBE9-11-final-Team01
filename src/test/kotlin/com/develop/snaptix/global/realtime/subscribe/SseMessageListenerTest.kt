package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.module.kotlin.jacksonObjectMapper

class SseMessageListenerTest {
    // dispatch 호출을 기록하는 가짜 매니저
    private class RecordingManager : SseConnectionManager {
        val dispatched = mutableListOf<Pair<SseChannelKey, SseEvent>>()

        override fun connect(
            key: SseChannelKey,
            userId: String,
        ): SseEmitter = throw UnsupportedOperationException()

        override fun dispatch(
            key: SseChannelKey,
            event: SseEvent,
        ) {
            dispatched += key to event
        }

        override fun close(key: SseChannelKey) = Unit

        override fun activeConnections(): Int = 0
    }

    private val mapper = jacksonObjectMapper()

    private fun listenerWith(manager: SseConnectionManager) = SseMessageListener(manager, mapper)

    private fun message(
        channel: String,
        body: String,
    ) = DefaultMessage(channel.toByteArray(), body.toByteArray())

    @Test
    fun `정상 메시지는 파싱해 dispatch 한다`() {
        val manager = RecordingManager()
        val body = """{"name":"READY_TO_PAY","data":{"orderId":"order-1"},"terminal":false}"""

        listenerWith(manager).onMessage(message("sse:order:order-1", body), null)

        assertThat(manager.dispatched).hasSize(1)
        val (key, event) = manager.dispatched.first()
        assertThat(key).isEqualTo(SseChannelKey("order", "order-1"))
        assertThat(event.name).isEqualTo("READY_TO_PAY")
        assertThat(event.terminal).isFalse()
    }

    @Test
    fun `역직렬화 실패 메시지는 무시한다`() {
        val manager = RecordingManager()
        listenerWith(manager).onMessage(message("sse:order:order-1", "not-json"), null)
        assertThat(manager.dispatched).isEmpty()
    }

    @Test
    fun `sse 접두사가 아닌 채널은 무시한다`() {
        val manager = RecordingManager()
        val body = """{"name":"READY_TO_PAY","data":null,"terminal":false}"""
        listenerWith(manager).onMessage(message("order:order-1", body), null)
        assertThat(manager.dispatched).isEmpty()
    }

    @Test
    fun `parseChannel 은 sse_resource_id 를 키로 만든다`() {
        assertThat(SseMessageListener.parseChannel("sse:order:abc-123"))
            .isEqualTo(SseChannelKey("order", "abc-123"))
    }

    @Test
    fun `parseChannel 은 형식 위반 시 null`() {
        assertThat(SseMessageListener.parseChannel("sse:order:")).isNull() // id 빈값
        assertThat(SseMessageListener.parseChannel("sse:onlyresource")).isNull() // 구분자 없음
        assertThat(SseMessageListener.parseChannel("order:abc")).isNull() // 접두사 없음
    }
}
