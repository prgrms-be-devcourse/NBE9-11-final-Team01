package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.payment.dto.MockPaymentApproveRequest
import com.develop.snaptix.domain.payment.dto.MockPaymentApproveResponse
import com.develop.snaptix.domain.payment.repository.PaymentReservation
import com.develop.snaptix.domain.payment.repository.PaymentReservationRepository
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.PaymentApproveGuardGateway
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class MockPaymentApproveService(
    private val paymentReservationRepository: PaymentReservationRepository,
    private val paymentApproveGuardGateway: PaymentApproveGuardGateway,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
    private val redisTtlProperties: RedisTtlProperties,
) {
    fun approve(
        userId: Long,
        request: MockPaymentApproveRequest,
    ): MockPaymentApproveResponse {
        val orderId = UUID.fromString(request.orderId)
        val reservation = findReservation(request.orderId)

        validateOwner(reservation, userId)
        validatePayableStatus(reservation)
        validateHoldWindow(orderId, reservation)

        paymentApproveGuardGateway.tryApprove(orderId)

        return MockPaymentApproveResponse(orderId = request.orderId)
    }

    private fun findReservation(orderId: String): PaymentReservation =
        paymentReservationRepository.findByOrderId(orderId)
            ?: throw BusinessException(ErrorCode.ORDER_NOT_FOUND)

    private fun validateOwner(
        reservation: PaymentReservation,
        userId: Long,
    ) {
        if (reservation.userId != userId) {
            throw BusinessException(ErrorCode.ORDER_ACCESS_DENIED)
        }
    }

    private fun validatePayableStatus(reservation: PaymentReservation) {
        if (reservation.status != ReservationStatus.PENDING_PAYMENT) {
            throw BusinessException(ErrorCode.ORDER_NOT_PAYABLE)
        }
    }

    private fun validateHoldWindow(
        orderId: UUID,
        reservation: PaymentReservation,
    ) {
        if (isHoldExpired(orderId, reservation.createdAt)) {
            throw BusinessException(ErrorCode.ORDER_HOLD_EXPIRED)
        }
    }

    private fun isHoldExpired(
        orderId: UUID,
        createdAt: Instant,
    ): Boolean {
        if (orderHoldRedisGateway.exists(orderId)) {
            return false
        }
        return createdAt.plus(redisTtlProperties.orderHold).isBefore(Instant.now())
    }
}
