package com.develop.snaptix.global.realtime.testing

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.StateReconstructor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class MockSseConnectionManagerTest {
    private val key = SseChannelKey("order", "order-1")
    private val userId = "user-1"

    /** 고정 결과를 돌려주는 소유권 포트 */
    private fun ownership(result: OwnershipResult) = OwnershipChecker { _, _ -> result }

    /** 고정 이벤트(또는 null)를 돌려주는 재구성 포트 */
    private fun reconstructor(event: SseEvent?) = StateReconstructor { event }

    /** 동일 스레드 실행기로 dispatch를 동기화(결정적 테스트) */
    private fun manager(
        result: OwnershipResult = OwnershipResult.OWNED,
        reconstruct: SseEvent? = null,
    ) = MockSseConnectionManager(
        ownershipChecker = ownership(result),
        stateReconstructor = reconstructor(reconstruct),
        sendExecutor = { it.run() },
    )

    @Test
    fun `OWNED 면 연결되고 활성 연결이 1이다`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)
        assertThat(sut.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `FORBIDDEN 이면 403 BusinessException`() {
        val sut = manager(OwnershipResult.FORBIDDEN)
        assertThatThrownBy { sut.connect(key, userId) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).httpStatus }
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `NOT_FOUND 이면 404 BusinessException`() {
        val sut = manager(OwnershipResult.NOT_FOUND)
        assertThatThrownBy { sut.connect(key, userId) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).httpStatus }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `연결 직후 재구성 이벤트가 있으면 1회 전송된다`() {
        val readyToPay = SseEvent.ongoing("READY_TO_PAY", mapOf("orderId" to "order-1"))
        val sut = manager(OwnershipResult.OWNED, reconstruct = readyToPay)
        sut.connect(key, userId)
        assertThat(sut.lastDispatched(key)).isEqualTo(readyToPay)
    }

    @Test
    fun `terminal 이벤트는 전송 후 연결을 정리한다`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)

        sut.dispatch(key, SseEvent.terminal("TICKET_ISSUED", "qr"))

        assertThat(sut.activeConnections()).isEqualTo(0)
        assertThat(sut.lastDispatched(key)?.name).isEqualTo("TICKET_ISSUED")
    }

    @Test
    fun `READY_TO_PAY 는 연결을 유지한다`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)

        sut.dispatch(key, SseEvent.ongoing("READY_TO_PAY", "x"))

        assertThat(sut.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `연결이 없으면 dispatch 는 no-op 이다`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.dispatch(key, SseEvent.ongoing("READY_TO_PAY", "x"))
        assertThat(sut.emitted(key)).isEmpty()
    }

    @Test
    fun `simulateTimeout 은 연결을 정리한다`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)

        sut.simulateTimeout(key)

        assertThat(sut.activeConnections()).isEqualTo(0)
    }
}
