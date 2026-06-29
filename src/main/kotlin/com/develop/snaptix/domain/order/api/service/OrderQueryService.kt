package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.api.dto.OrderStatus
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse
import com.develop.snaptix.domain.order.api.port.OrderQueryPort
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.repository.OrderOwnerStore
import com.develop.snaptix.domain.reservation.repository.ReservationQuery
import com.develop.snaptix.domain.ticket.repository.TicketQuery
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * `GET /api/v1/orders/{orderId}` 폴링/재구성 서비스 (PR-13, Story 10.1-B, 4.2).
 *
 * ## 소유권 검증
 * 1. `reservations` 행 존재 → `user_id` 일치 여부 확인 (IDOR 방어)
 * 2. 행 없음(PENDING 처리 전) → `order:owner:{orderId}` 키로 검증
 *
 * ## 상태 판정
 * SSOT = `reservations.status`. `ORDER_HOLD` Redis 키에 **의존하지 않고**
 * `created_at + orderHold(5분) > now()` 윈도우로 판정한다 (결정 D4, Story 8-3).
 * Redis 재부팅으로 홀드 키가 소실돼도 유효 주문을 READY_TO_PAY로 재구성한다.
 *
 * | reservations 상태         | 홀드 윈도우 | 응답 status    | 추가 필드            |
 * |--------------------------|------------|----------------|----------------------|
 * | 행 없음 + owner 키 존재    | —          | PENDING        | message              |
 * | PENDING_PAYMENT + 윈도우 내 | 유효      | READY_TO_PAY   | paymentDeadline      |
 * | PENDING_PAYMENT + 만료    | 만료        | PENDING        | message              |
 * | CONFIRMED                | —          | CONFIRMED      | ticketCode (null 가능)|
 * | CANCELLED                | —          | FAILED         | —                    |
 * | RELEASED                 | —          | EXPIRED        | —                    |
 */
@Service
class OrderQueryService(
    private val reservationQuery: ReservationQuery,
    private val orderOwnerStore: OrderOwnerStore,
    private val ticketQuery: TicketQuery,
    private val redisTtlProperties: RedisTtlProperties,
) : OrderQueryPort {
    override fun getStatus(
        userId: Long,
        orderId: String,
    ): OrderStatusResponse {
        val reservation =
            reservationQuery.findByOrderId(orderId)
                ?: return resolveNoReservation(userId, orderId)

        // ── 예약 행 존재: IDOR 방어 ─────────────────────────────────────────
        if (reservation.userId != userId) throw BusinessException(ErrorCode.ORDER_ACCESS_DENIED)

        return when (reservation.status) {
            ReservationStatus.PENDING_PAYMENT -> resolvePendingPayment(orderId, reservation.createdAt)
            ReservationStatus.CONFIRMED -> resolveConfirmed(orderId)
            ReservationStatus.CANCELLED -> OrderStatusResponse(orderId = orderId, status = OrderStatus.FAILED)
            ReservationStatus.RELEASED -> OrderStatusResponse(orderId = orderId, status = OrderStatus.EXPIRED)
        }
    }

    /**
     * 예약 행 없음(PENDING 처리 전) — owner 키 기반 소유권 검증.
     *
     * throw 분리 목적: ThrowsCount(max=2) detekt 규칙 준수.
     */
    private fun resolveNoReservation(
        userId: Long,
        orderId: String,
    ): OrderStatusResponse {
        val ownerId =
            orderOwnerStore.findOwnerUserId(orderId)
                ?: throw BusinessException(ErrorCode.ORDER_NOT_FOUND)

        if (ownerId != userId) throw BusinessException(ErrorCode.ORDER_ACCESS_DENIED)

        return OrderStatusResponse(
            orderId = orderId,
            status = OrderStatus.PENDING,
            message = "주문이 처리 대기 중입니다. 잠시 후 다시 확인해주세요.",
        )
    }

    /**
     * PENDING_PAYMENT 상태 판정.
     *
     * ORDER_HOLD 키 없이 `created_at + orderHold` 윈도우만으로 READY_TO_PAY 재구성.
     * 윈도우 초과 시 PENDING 반환 — HoldExpiryWorker(@Scheduled)가 곧 RELEASED 전이.
     */
    private fun resolvePendingPayment(
        orderId: String,
        createdAt: Instant,
    ): OrderStatusResponse {
        val paymentDeadline = createdAt.plus(redisTtlProperties.orderHold)
        return if (Instant.now().isBefore(paymentDeadline)) {
            OrderStatusResponse(
                orderId = orderId,
                status = OrderStatus.READY_TO_PAY,
                paymentDeadline = paymentDeadline,
            )
        } else {
            // 홀드 만료 — HoldExpiryWorker가 곧 RELEASED로 전이
            OrderStatusResponse(
                orderId = orderId,
                status = OrderStatus.PENDING,
                message = "결제 대기 시간이 만료되었습니다. 잠시 후 상태가 업데이트됩니다.",
            )
        }
    }

    /**
     * CONFIRMED 상태 판정.
     *
     * ticketCode는 결제 팀 산출물(tickets 테이블). 발급 전이거나 조회 불가 시 null 반환.
     */
    private fun resolveConfirmed(orderId: String): OrderStatusResponse = OrderStatusResponse(
        orderId = orderId,
        status = OrderStatus.CONFIRMED,
        ticketCode = ticketQuery.findTicketCodeByOrderId(orderId),
    )
}
