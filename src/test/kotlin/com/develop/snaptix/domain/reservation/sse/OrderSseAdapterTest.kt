package com.develop.snaptix.domain.reservation.sse

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.repository.ReservationView
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class OrderSseAdapterTest {
    private val key = SseChannelKey("order", "order-1")
    private val userId = 100L

    private fun view(
        owner: Long = userId,
        status: ReservationStatus = ReservationStatus.PENDING_PAYMENT,
        createdAt: Instant = Instant.now(),
    ) = ReservationView(userId = owner, status = status, createdAt = createdAt)

    private fun adapter(
        reservation: ReservationView? = null,
        ownerKey: Long? = null,
        orderHold: Duration = Duration.ofMinutes(5),
    ) = OrderSseAdapter(
        reservationQuery = { reservation },
        orderOwnerStore = { ownerKey },
        redisTtlProperties = RedisTtlProperties(orderHold = orderHold),
    )

    // ── 소유권 check ──────────────────────────────────────────────────

    @Test
    fun `예약 행 소유자 일치면 OWNED`() {
        val sut = adapter(reservation = view(owner = userId))
        assertThat(sut.check(key, "100")).isEqualTo(OwnershipResult.OWNED)
    }

    @Test
    fun `예약 행 소유자 불일치면 FORBIDDEN`() {
        val sut = adapter(reservation = view(owner = 999L))
        assertThat(sut.check(key, "100")).isEqualTo(OwnershipResult.FORBIDDEN)
    }

    @Test
    fun `행 부재 + order_owner 일치면 OWNED`() {
        val sut = adapter(reservation = null, ownerKey = userId)
        assertThat(sut.check(key, "100")).isEqualTo(OwnershipResult.OWNED)
    }

    @Test
    fun `행 부재 + order_owner 불일치면 FORBIDDEN`() {
        val sut = adapter(reservation = null, ownerKey = 999L)
        assertThat(sut.check(key, "100")).isEqualTo(OwnershipResult.FORBIDDEN)
    }

    @Test
    fun `행 부재 + order_owner 없음이면 NOT_FOUND`() {
        val sut = adapter(reservation = null, ownerKey = null)
        assertThat(sut.check(key, "100")).isEqualTo(OwnershipResult.NOT_FOUND)
    }

    @Test
    fun `userId 가 숫자가 아니면 FORBIDDEN`() {
        val sut = adapter(reservation = view())
        assertThat(sut.check(key, "not-a-number")).isEqualTo(OwnershipResult.FORBIDDEN)
    }

    // ── 재구성 reconstruct ────────────────────────────────────────────

    @Test
    fun `PENDING + 윈도우 내면 READY_TO_PAY(연결 유지)`() {
        val createdAt = Instant.now()
        val sut = adapter(reservation = view(status = ReservationStatus.PENDING_PAYMENT, createdAt = createdAt))

        val event = sut.reconstruct(key)

        assertThat(event?.name).isEqualTo("READY_TO_PAY")
        assertThat(event?.terminal).isFalse()
        val data = event?.data as Map<*, *>
        assertThat(data["type"]).isEqualTo("READY_TO_PAY")
        assertThat(data["orderId"]).isEqualTo(key.id)
        assertThat(data["status"]).isEqualTo(ReservationStatus.PENDING_PAYMENT.name)
        assertThat(data["message"]).isEqualTo("좌석이 확보되었습니다. 결제 대기 시간 내에 결제를 완료해주세요.")
        assertThat(data["paymentDeadline"]).isEqualTo(createdAt.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `READY_TO_PAY paymentDeadline은 설정된 홀드 duration으로 계산한다`() {
        val createdAt = Instant.now()
        val sut =
            adapter(
                reservation = view(status = ReservationStatus.PENDING_PAYMENT, createdAt = createdAt),
                orderHold = Duration.ofMinutes(3),
            )

        val event = sut.reconstruct(key)

        val data = event?.data as Map<*, *>
        assertThat(data["paymentDeadline"]).isEqualTo(createdAt.plus(Duration.ofMinutes(3)))
    }

    @Test
    fun `PENDING + 윈도우 만료면 null`() {
        val expired = Instant.now().minus(Duration.ofMinutes(10))
        val sut = adapter(reservation = view(status = ReservationStatus.PENDING_PAYMENT, createdAt = expired))
        assertThat(sut.reconstruct(key)).isNull()
    }

    @Test
    fun `CONFIRMED 면 TICKET_ISSUED(터미널)`() {
        val sut = adapter(reservation = view(status = ReservationStatus.CONFIRMED))
        val event = sut.reconstruct(key)
        assertThat(event?.name).isEqualTo("TICKET_ISSUED")
        assertThat(event?.terminal).isTrue()
    }

    @Test
    fun `CANCELLED 면 ORDER_FAILED(터미널)`() {
        val sut = adapter(reservation = view(status = ReservationStatus.CANCELLED))
        assertThat(sut.reconstruct(key)?.name).isEqualTo("ORDER_FAILED")
    }

    @Test
    fun `RELEASED 면 PAYMENT_TIMEOUT(터미널)`() {
        val sut = adapter(reservation = view(status = ReservationStatus.RELEASED))
        assertThat(sut.reconstruct(key)?.name).isEqualTo("PAYMENT_TIMEOUT")
    }

    @Test
    fun `예약 행 없으면 재구성 null`() {
        val sut = adapter(reservation = null)
        assertThat(sut.reconstruct(key)).isNull()
    }
}
