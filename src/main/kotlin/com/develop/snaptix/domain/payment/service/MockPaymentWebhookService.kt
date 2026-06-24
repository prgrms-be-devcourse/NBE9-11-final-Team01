package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.payment.dto.MockPaymentStatus
import com.develop.snaptix.domain.payment.dto.MockPaymentWebhookRequest
import com.develop.snaptix.domain.payment.dto.MockPaymentWebhookResponse
import com.develop.snaptix.domain.payment.repository.PaymentReservationRepository
import com.develop.snaptix.domain.payment.repository.PaymentWebhookProcessResult
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.gateway.WebhookGuardRedisGateway
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MockPaymentWebhookService(
    private val paymentReservationRepository: PaymentReservationRepository,
    private val webhookGuardRedisGateway: WebhookGuardRedisGateway,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
    private val stockRedisGateway: StockRedisGateway,
) {
    fun handle(request: MockPaymentWebhookRequest): MockPaymentWebhookResponse {
        val orderId = UUID.fromString(request.orderId)
        val result =
            when (request.paymentStatus) {
                MockPaymentStatus.SUCCESS -> paymentReservationRepository.confirmIfPending(request.orderId)
                MockPaymentStatus.FAIL -> paymentReservationRepository.cancelIfPending(request.orderId)
            } ?: throw BusinessException(ErrorCode.ORDER_NOT_FOUND)

        if (shouldRunSideEffects(request.paymentStatus, result)) {
            releaseHold(orderId)
            compensateStockIfPaymentFailed(orderId, request.paymentStatus, result)
        }

        webhookGuardRedisGateway.markProcessed(orderId)

        return MockPaymentWebhookResponse(
            orderId = request.orderId,
            processed = result.processed,
            message = if (result.processed) MESSAGE_PROCESSED else MESSAGE_SKIPPED,
        )
    }

    private fun releaseHold(orderId: UUID) {
        orderHoldRedisGateway.delete(orderId)
    }

    private fun shouldRunSideEffects(
        paymentStatus: MockPaymentStatus,
        result: PaymentWebhookProcessResult,
    ): Boolean {
        if (result.processed) {
            return true
        }

        return when (paymentStatus) {
            MockPaymentStatus.SUCCESS -> result.reservation.status == ReservationStatus.CONFIRMED
            MockPaymentStatus.FAIL -> result.reservation.status == ReservationStatus.CANCELLED
        }
    }

    private fun compensateStockIfPaymentFailed(
        orderId: UUID,
        paymentStatus: MockPaymentStatus,
        result: PaymentWebhookProcessResult,
    ) {
        if (paymentStatus == MockPaymentStatus.FAIL) {
            stockRedisGateway.compensate(result.reservation.zoneId, orderId)
        }
    }

    companion object {
        private const val MESSAGE_PROCESSED = "결제 결과가 처리되었습니다."
        private const val MESSAGE_SKIPPED = "이미 처리된 결제 결과입니다."
    }
}
