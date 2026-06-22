package com.develop.snaptix.global.realtime

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.StateReconstructor
import com.develop.snaptix.global.realtime.testing.MockSseConnectionManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * SseConnectionManager 계약 테스트 (PR-10).
 *
 * 목(Mock)과 실제(InMemory)가 **동일 외부 동작**을 함을 보증한다 → DI 교체 안전성.
 * 두 하위 클래스가 같은 @Test 묶음을 상속해 각 구현으로 실행한다.
 */
abstract class SseConnectionManagerContract {
    private val key = SseChannelKey("order", "order-1")
    private val userId = "user-1"

    protected abstract fun manager(
        result: OwnershipResult = OwnershipResult.OWNED,
        reconstruct: SseEvent? = null,
    ): SseConnectionManager

    @Test
    fun `OWNED 면 연결되고 활성 1`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)
        assertThat(sut.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `FORBIDDEN 이면 403`() {
        val sut = manager(OwnershipResult.FORBIDDEN)
        assertThatThrownBy { sut.connect(key, userId) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).httpStatus }
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `NOT_FOUND 이면 404`() {
        val sut = manager(OwnershipResult.NOT_FOUND)
        assertThatThrownBy { sut.connect(key, userId) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).httpStatus }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `terminal 이벤트는 전송 후 정리(활성 0)`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)
        sut.dispatch(key, SseEvent.terminal("TICKET_ISSUED", "qr"))
        assertThat(sut.activeConnections()).isEqualTo(0)
    }

    @Test
    fun `READY_TO_PAY 는 연결 유지(활성 1)`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)
        sut.dispatch(key, SseEvent.ongoing("READY_TO_PAY", "x"))
        assertThat(sut.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `연결 직후 재구성 이벤트가 있으면 유지(비터미널)`() {
        val sut = manager(OwnershipResult.OWNED, reconstruct = SseEvent.ongoing("READY_TO_PAY", "x"))
        sut.connect(key, userId)
        assertThat(sut.activeConnections()).isEqualTo(1)
    }

    @Test
    fun `연결 없으면 dispatch 는 no-op`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.dispatch(key, SseEvent.ongoing("READY_TO_PAY", "x"))
        assertThat(sut.activeConnections()).isEqualTo(0)
    }

    @Test
    fun `같은 키 재연결 시 활성 1 유지`() {
        val sut = manager(OwnershipResult.OWNED)
        sut.connect(key, userId)
        sut.connect(key, userId)
        assertThat(sut.activeConnections()).isEqualTo(1)
    }
}

/** 목 구현으로 계약 검증. dispatch 결정성을 위해 동일 스레드 실행기 주입. */
class MockContractTest : SseConnectionManagerContract() {
    override fun manager(
        result: OwnershipResult,
        reconstruct: SseEvent?,
    ): SseConnectionManager =
        MockSseConnectionManager(
            ownershipChecker = { _, _ -> result },
            stateReconstructor = { reconstruct },
            sendExecutor = { it.run() },
        )
}

/** 실제 코어 구현으로 계약 검증. */
class InMemoryContractTest : SseConnectionManagerContract() {
    override fun manager(
        result: OwnershipResult,
        reconstruct: SseEvent?,
    ): SseConnectionManager =
        InMemorySseConnectionManager(
            ownershipCheckers = mapOf("order" to OwnershipChecker { _, _ -> result }),
            stateReconstructors = mapOf("order" to StateReconstructor { reconstruct }),
        )
}
