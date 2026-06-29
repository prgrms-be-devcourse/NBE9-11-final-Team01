package com.develop.snaptix.domain.reservation.sse

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.repository.OrderOwnerStore
import com.develop.snaptix.domain.reservation.repository.ReservationQuery
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.StateReconstructor
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 주문(order) 채널용 SSE 도메인 어댑터 (PR-07).
 *
 * `global.realtime` 의 두 포트(OwnershipChecker·StateReconstructor)를 구현하는 **브리지**.
 * SSE 모듈은 reservation 을 모르고, 도메인 지식(예약 조회·order:owner)은 이 어댑터가 캡슐화한다.
 *
 * 빈 이름 `"order"` 단일 빈이 두 포트를 모두 구현 → InMemorySseConnectionManager 의
 * `Map<String, OwnershipChecker>`·`Map<String, StateReconstructor>` 양쪽에 `key.resource="order"`
 * 로 매핑된다(빈 이름 충돌 회피).
 */
@Component("order")
class OrderSseAdapter(
    private val reservationQuery: ReservationQuery,
    private val orderOwnerStore: OrderOwnerStore,
    private val redisTtlProperties: RedisTtlProperties,
) : OwnershipChecker,
    StateReconstructor {
    /**
     * 소유권 검증 (Story 2.1).
     * - 예약 행 존재 → user_id 일치 OWNED / 불일치 FORBIDDEN
     * - 행 부재(PENDING 처리 전) → order:owner 키로 검증 (없음 NOT_FOUND)
     */
    override fun check(
        key: SseChannelKey,
        userId: String,
    ): OwnershipResult {
        val requesterId = userId.toLongOrNull() ?: return OwnershipResult.FORBIDDEN
        val reservation = reservationQuery.findByOrderId(key.id)

        return when {
            reservation != null ->
                if (reservation.userId == requesterId) OwnershipResult.OWNED else OwnershipResult.FORBIDDEN

            else ->
                when (orderOwnerStore.findOwnerUserId(key.id)) {
                    null -> OwnershipResult.NOT_FOUND
                    requesterId -> OwnershipResult.OWNED
                    else -> OwnershipResult.FORBIDDEN
                }
        }
    }

    /**
     * 재연결 시 DB 기준 상태 재구성 (결정 D4, Story 4.2/10.1-B).
     * ORDER_HOLD 키에 의존하지 않고 status + created_at 홀드 윈도우로 판정한다.
     */
    override fun reconstruct(key: SseChannelKey): SseEvent? {
        val reservation = reservationQuery.findByOrderId(key.id) ?: return null
        val data = mapOf("orderId" to key.id, "status" to reservation.status.name)

        return when (reservation.status) {
            ReservationStatus.PENDING_PAYMENT -> {
                val paymentDeadline = paymentDeadline(reservation.createdAt)
                if (withinHoldWindow(paymentDeadline)) {
                    SseEvent.ongoing(EVENT_READY_TO_PAY, readyToPayData(key.id, paymentDeadline))
                } else {
                    null
                }
            }
            ReservationStatus.CONFIRMED -> SseEvent.terminal(EVENT_TICKET_ISSUED, data)
            ReservationStatus.CANCELLED -> SseEvent.terminal(EVENT_ORDER_FAILED, data)
            ReservationStatus.RELEASED -> SseEvent.terminal(EVENT_PAYMENT_TIMEOUT, data)
        }
    }

    private fun withinHoldWindow(paymentDeadline: Instant): Boolean = Instant.now().isBefore(paymentDeadline)

    private fun paymentDeadline(createdAt: Instant): Instant = createdAt.plus(redisTtlProperties.orderHold)

    private fun readyToPayData(
        orderId: String,
        paymentDeadline: Instant,
    ): Map<String, Any> = mapOf(
        "type" to EVENT_READY_TO_PAY,
        "orderId" to orderId,
        "status" to ReservationStatus.PENDING_PAYMENT.name,
        "message" to READY_TO_PAY_MESSAGE,
        "paymentDeadline" to paymentDeadline,
    )

    companion object {
        private const val EVENT_READY_TO_PAY = "READY_TO_PAY"
        private const val EVENT_TICKET_ISSUED = "TICKET_ISSUED"
        private const val EVENT_ORDER_FAILED = "ORDER_FAILED"
        private const val EVENT_PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT"
        private const val READY_TO_PAY_MESSAGE = "좌석이 확보되었습니다. 결제 대기 시간 내에 결제를 완료해주세요."
    }
}
