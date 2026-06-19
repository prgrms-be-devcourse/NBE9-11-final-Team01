package com.develop.snaptix.global.realtime

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.StateReconstructor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class InMemorySseConnectionManagerTest {
    private val key = SseChannelKey("order", "order-1")
    private val userId = "user-1"

    private fun sut(
        result: OwnershipResult = OwnershipResult.OWNED,
        reconstruct: SseEvent? = null,
    ) = InMemorySseConnectionManager(
        ownershipCheckers = mapOf("order" to OwnershipChecker { _, _ -> result }),
        stateReconstructors = mapOf("order" to StateReconstructor { reconstruct }),
    )

    @Test
    fun `OWNED 면 연결되고 활성 연결이 1이다`() {
        val manager = sut(OwnershipResult.OWNED)
        manager.connect(key, userId)
        assertThat(manager.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `FORBIDDEN 이면 403 BusinessException`() {
        val manager = sut(OwnershipResult.FORBIDDEN)
        assertThatThrownBy { manager.connect(key, userId) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).httpStatus }
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `NOT_FOUND 이면 404 BusinessException`() {
        val manager = sut(OwnershipResult.NOT_FOUND)
        assertThatThrownBy { manager.connect(key, userId) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).httpStatus }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `등록된 OwnershipChecker 가 없으면 IllegalStateException`() {
        val manager = InMemorySseConnectionManager() // 어댑터 없음
        assertThatThrownBy { manager.connect(key, userId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `연결 직후 재구성 이벤트가 있으면 1회 전송된다`() {
        val readyToPay = SseEvent.ongoing("READY_TO_PAY", mapOf("orderId" to "order-1"))
        val manager = sut(OwnershipResult.OWNED, reconstruct = readyToPay)
        manager.connect(key, userId)
        // 재구성 후 READY_TO_PAY 는 비터미널이므로 연결 유지
        assertThat(manager.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `terminal 이벤트는 전송 후 연결을 정리한다`() {
        val manager = sut(OwnershipResult.OWNED)
        manager.connect(key, userId)

        manager.dispatch(key, SseEvent.terminal("TICKET_ISSUED", "qr"))

        assertThat(manager.activeConnections()).isEqualTo(0)
    }

    @Test
    fun `같은 키 재연결 시 활성 연결은 1개로 유지된다`() {
        val manager = sut(OwnershipResult.OWNED)
        manager.connect(key, userId)
        manager.connect(key, userId)
        assertThat(manager.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `연결이 없으면 dispatch 는 no-op 이다`() {
        val manager = sut(OwnershipResult.OWNED)
        manager.dispatch(key, SseEvent.ongoing("READY_TO_PAY", "x")) // 예외 없이 통과
        assertThat(manager.activeConnections()).isEqualTo(0)
    }

    @Test
    fun `heartbeat 는 활성 연결을 유지한다`() {
        val manager = sut(OwnershipResult.OWNED)
        manager.connect(key, userId)
        manager.heartbeat() // 신선한 연결엔 ping 정상 전송
        assertThat(manager.activeConnections()).isEqualTo(1)
    }
}
