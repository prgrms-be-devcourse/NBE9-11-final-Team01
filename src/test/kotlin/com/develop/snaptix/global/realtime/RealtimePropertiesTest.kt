package com.develop.snaptix.global.realtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class RealtimePropertiesTest {
    @Test
    fun `기본값은 8분 타임아웃과 30초 heartbeat`() {
        val props = RealtimeProperties()
        assertThat(props.timeoutMillis()).isEqualTo(Duration.ofMinutes(8).toMillis())
        assertThat(props.heartbeatInterval).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `timeout 이 0 이하면 예외`() {
        assertThatThrownBy { RealtimeProperties(timeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `heartbeat-interval 이 0 이하면 예외`() {
        assertThatThrownBy { RealtimeProperties(heartbeatInterval = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `timeout 이 heartbeat-interval 보다 작거나 같으면 예외`() {
        assertThatThrownBy {
            RealtimeProperties(timeout = Duration.ofSeconds(30), heartbeatInterval = Duration.ofSeconds(30))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
