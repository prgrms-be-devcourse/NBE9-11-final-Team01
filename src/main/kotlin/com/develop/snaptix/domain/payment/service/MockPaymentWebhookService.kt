package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.payment.dto.MockPaymentStatus
import com.develop.snaptix.domain.payment.dto.MockPaymentWebhookRequest
import com.develop.snaptix.domain.payment.dto.MockPaymentWebhookResponse
import com.develop.snaptix.domain.payment.repository.PaymentReservationRepository
import com.develop.snaptix.domain.payment.repository.PaymentWebhookProcessResult
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.ErrorResponse
import com.develop.snaptix.global.exception.FieldValidationException
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.gateway.WebhookGuardRedisGateway
import jakarta.validation.Validator
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class MockPaymentWebhookService(
    private val paymentReservationRepository: PaymentReservationRepository,
    private val webhookGuardRedisGateway: WebhookGuardRedisGateway,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
    private val stockRedisGateway: StockRedisGateway,
    private val signatureVerifier: MockPaymentWebhookSignatureVerifier,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
) {
    fun handle(
        rawBody: String,
        signature: String?,
    ): MockPaymentWebhookResponse {
        verifySignature(rawBody, signature)

        val request = parseRequest(rawBody)
        validateRequest(request)
        val orderId = parseOrderId(request.orderId)

        if (webhookGuardRedisGateway.isProcessed(orderId)) {
            return skipped(request.orderId)
        }

        val result =
            when (request.paymentStatus) {
                MockPaymentStatus.SUCCESS -> paymentReservationRepository.confirmIfPending(request.orderId)
                MockPaymentStatus.FAIL -> paymentReservationRepository.cancelIfPending(request.orderId)
            } ?: throw BusinessException(ErrorCode.ORDER_NOT_FOUND)

        if (shouldRunSideEffects(request.paymentStatus, result)) {
            releaseHold(orderId)
            releaseClaimIfPaymentSucceeded(orderId, request.paymentStatus, result)
            compensateStockIfPaymentFailed(orderId, request.paymentStatus, result)
        }

        webhookGuardRedisGateway.markProcessed(orderId)

        return if (result.processed) {
            processed(request.orderId)
        } else {
            skipped(request.orderId)
        }
    }

    private fun verifySignature(
        rawBody: String,
        signature: String?,
    ) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw BusinessException(ErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID)
        }
    }

    private fun parseRequest(rawBody: String): MockPaymentWebhookRequest = try {
        objectMapper.readValue(rawBody, MockPaymentWebhookRequest::class.java)
    } catch (exception: JacksonException) {
        throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "Webhook 요청 본문이 유효하지 않습니다.", exception)
    }

    private fun validateRequest(request: MockPaymentWebhookRequest) {
        val fieldErrors =
            validator
                .validate(request)
                .map {
                    ErrorResponse.FieldError(
                        field = it.propertyPath.toString(),
                        reason = it.message,
                    )
                }.sortedBy { it.field }

        if (fieldErrors.isNotEmpty()) {
            throw FieldValidationException(fieldErrors)
        }
    }

    private fun parseOrderId(orderId: String): UUID = try {
        UUID.fromString(orderId)
    } catch (exception: IllegalArgumentException) {
        throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "orderId는 올바른 UUID 형식이어야 합니다.", exception)
    }

    private fun releaseHold(orderId: UUID) {
        orderHoldRedisGateway.delete(orderId)
    }

    private fun releaseClaimIfPaymentSucceeded(
        orderId: UUID,
        paymentStatus: MockPaymentStatus,
        result: PaymentWebhookProcessResult,
    ) {
        if (paymentStatus == MockPaymentStatus.SUCCESS) {
            stockRedisGateway.releaseClaim(result.reservation.zoneId, orderId)
        }
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

    private fun processed(orderId: String): MockPaymentWebhookResponse = MockPaymentWebhookResponse(
        orderId = orderId,
        processed = true,
        message = MESSAGE_PROCESSED,
    )

    private fun skipped(orderId: String): MockPaymentWebhookResponse = MockPaymentWebhookResponse(
        orderId = orderId,
        processed = false,
        message = MESSAGE_SKIPPED,
    )

    companion object {
        private const val MESSAGE_PROCESSED = "결제 결과가 처리되었습니다."
        private const val MESSAGE_SKIPPED = "이미 처리된 결제 결과입니다."
    }
}
