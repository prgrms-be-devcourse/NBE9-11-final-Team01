package com.develop.snaptix.global.realtime

import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.SseChannelSubscriber
import com.develop.snaptix.global.realtime.port.StateReconstructor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SSE 연결 누수 방지 검증 (PR-04).
 *
 * 종료 경로(터미널 dispatch·close)와 재연결에서 레지스트리·구독이 누수 없이 정리되는지 검증한다.
 * (onCompletion/onTimeout/onError 실제 발화는 MVC 레이어가 필요하므로 통합 테스트(IT)에서 보강)
 */
class SseLeakPreventionTest {
    private val key = SseChannelKey("order", "order-1")
    private val userId = "user-1"

    /** 구독/해제 호출을 기록하는 스파이 구독자 */
    private class RecordingSubscriber : SseChannelSubscriber {
        val subscribes = mutableListOf<SseChannelKey>()
        val unsubscribes = mutableListOf<SseChannelKey>()

        override fun subscribe(key: SseChannelKey) {
            subscribes += key
        }

        override fun unsubscribe(key: SseChannelKey) {
            unsubscribes += key
        }
    }

    private fun managerWith(subscriber: SseChannelSubscriber) =
        InMemorySseConnectionManager(
            ownershipCheckers = mapOf("order" to OwnershipChecker { _, _ -> OwnershipResult.OWNED }),
            stateReconstructors = mapOf("order" to StateReconstructor { null }),
            subscriber = subscriber,
        )

    @Test
    fun `터미널 dispatch 후 활성 0 + 구독 해제`() {
        val sub = RecordingSubscriber()
        val manager = managerWith(sub)
        manager.connect(key, userId)

        manager.dispatch(key, SseEvent.terminal("TICKET_ISSUED", "qr"))

        assertThat(manager.activeConnections()).isZero()
        assertThat(sub.unsubscribes).containsExactly(key)
    }

    @Test
    fun `close 후 활성 0 + 구독 해제`() {
        val sub = RecordingSubscriber()
        val manager = managerWith(sub)
        manager.connect(key, userId)

        manager.close(key)

        assertThat(manager.activeConnections()).isZero()
        assertThat(sub.unsubscribes).containsExactly(key)
    }

    @Test
    fun `재연결 시 구독은 1회만 + 활성 1개 유지`() {
        val sub = RecordingSubscriber()
        val manager = managerWith(sub)

        manager.connect(key, userId)
        manager.connect(key, userId) // 같은 키 재연결

        assertThat(manager.activeConnections()).isEqualTo(1)
        assertThat(sub.subscribes).containsExactly(key) // 재구독 안 함(구독 유지)
    }

    @Test
    fun `여러 연결을 모두 종료하면 활성 0으로 수렴한다`() {
        val sub = RecordingSubscriber()
        val manager = managerWith(sub)
        val keys = (1..5).map { SseChannelKey("order", "order-$it") }

        keys.forEach { manager.connect(it, userId) }
        assertThat(manager.activeConnections()).isEqualTo(5)

        keys.forEach { manager.close(it) }
        assertThat(manager.activeConnections()).isZero()
        assertThat(sub.unsubscribes).containsExactlyInAnyOrderElementsOf(keys)
    }

    @Test
    fun `close 는 멱등이다`() {
        val sub = RecordingSubscriber()
        val manager = managerWith(sub)
        manager.connect(key, userId)

        manager.close(key)
        manager.close(key) // 2회 호출 안전

        assertThat(manager.activeConnections()).isZero()
        assertThat(sub.unsubscribes).containsExactly(key) // 중복 해제 없음
    }
}
