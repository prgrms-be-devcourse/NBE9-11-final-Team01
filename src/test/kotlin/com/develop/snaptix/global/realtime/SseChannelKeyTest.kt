package com.develop.snaptix.global.realtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SseChannelKeyTest {
    @Test
    fun `redisChannel 은 sse_resource_id 포맷이다`() {
        val key = SseChannelKey("order", "abc-123")
        assertThat(key.redisChannel()).isEqualTo("sse:order:abc-123")
    }

    @Test
    fun `registryKey 는 resource_id 포맷이다`() {
        val key = SseChannelKey("payment", "pay-9")
        assertThat(key.registryKey()).isEqualTo("payment:pay-9")
    }

    @Test
    fun `resource 가 비면 예외`() {
        assertThatThrownBy { SseChannelKey(" ", "id") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `id 가 비면 예외`() {
        assertThatThrownBy { SseChannelKey("order", "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
