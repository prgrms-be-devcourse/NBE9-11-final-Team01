package com.develop.snaptix.global.realtime

import com.develop.snaptix.global.realtime.observability.SseObserver
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.StateReconstructor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * InMemorySseConnectionManager 가 연결/전송/정리 시 SseObserver hook 을 호출하는지 검증 (PR-08).
 */
class SseObservabilityTest {
    private val key = SseChannelKey("order", "order-1")
    private val userId = "user-1"

    private class RecordingObserver : SseObserver {
        var connects = 0
        var disconnects = 0
        var dispatches = 0
        var failures = 0

        override fun onConnect(key: SseChannelKey) {
            connects++
        }

        override fun onDisconnect(key: SseChannelKey) {
            disconnects++
        }

        override fun onDispatch(
            key: SseChannelKey,
            event: SseEvent,
        ) {
            dispatches++
        }

        override fun onDispatchFailure(
            key: SseChannelKey,
            cause: Throwable,
        ) {
            failures++
        }
    }

    private fun managerWith(observer: SseObserver) = InMemorySseConnectionManager(
        ownershipCheckers = mapOf("order" to OwnershipChecker { _, _ -> OwnershipResult.OWNED }),
        stateReconstructors = mapOf("order" to StateReconstructor { null }),
        observer = observer,
    )

    @Test
    fun `connect 시 onConnect 가 호출된다`() {
        val obs = RecordingObserver()
        managerWith(obs).connect(key, userId)
        assertThat(obs.connects).isEqualTo(1)
    }

    @Test
    fun `terminal dispatch 시 onDispatch 와 onDisconnect 가 호출된다`() {
        val obs = RecordingObserver()
        val manager = managerWith(obs)
        manager.connect(key, userId)

        manager.dispatch(key, SseEvent.terminal("TICKET_ISSUED", "x"))

        assertThat(obs.dispatches).isEqualTo(1)
        assertThat(obs.disconnects).isEqualTo(1) // 터미널 → 정리
    }

    @Test
    fun `close 시 onDisconnect 가 호출된다`() {
        val obs = RecordingObserver()
        val manager = managerWith(obs)
        manager.connect(key, userId)

        manager.close(key)

        assertThat(obs.disconnects).isEqualTo(1)
    }
}
