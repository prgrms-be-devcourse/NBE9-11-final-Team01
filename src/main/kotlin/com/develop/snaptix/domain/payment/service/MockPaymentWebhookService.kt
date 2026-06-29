package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.order.worker.release.ReleaseReason
import com.develop.snaptix.domain.order.worker.release.StockReleaseService
import com.develop.snaptix.domain.payment.dto.MockPaymentStatus
import com.develop.snaptix.domain.payment.dto.MockPaymentWebhookRequest
import com.develop.snaptix.domain.payment.dto.MockPaymentWebhookResponse
import com.develop.snaptix.domain.payment.repository.PaymentReservationRepository
import com.develop.snaptix.domain.ticket.service.TicketService
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.ErrorResponse
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.gateway.WebhookGuardRedisGateway
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class MockPaymentWebhookService(
    private val paymentReservationRepository: PaymentReservationRepository,
    private val webhookGuardRedisGateway: WebhookGuardRedisGateway,
    private val signatureVerifier: MockPaymentWebhookSignatureVerifier,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
    private val stockReleaseService: StockReleaseService,
    private val paymentConfirmRedisCleanupService: PaymentConfirmRedisCleanupService,
    private val ticketService: TicketService,
) {
    fun handle(
        rawBody: String,
        signature: String?,
    ): MockPaymentWebhookResponse {
        verifySignature(rawBody, signature)

        val request = parseRequest(rawBody)
        validateRequest(request)
        val orderId = parseOrderId(request.orderId)

        paymentReservationRepository.findByOrderId(request.orderId)
            ?: throw BusinessException(ErrorCode.ORDER_NOT_FOUND)

        if (isAlreadyProcessed(orderId)) {
            return skipped(request.orderId)
        }

        val result =
            when (request.paymentStatus) {
                MockPaymentStatus.SUCCESS -> paymentReservationRepository.confirmIfPending(request.orderId)
                MockPaymentStatus.FAIL -> paymentReservationRepository.cancelIfPending(request.orderId)
            } ?: throw BusinessException(ErrorCode.ORDER_NOT_FOUND)

        return if (result.processed) {
            markProcessed(orderId)
            dispatchRedisCleanup(request.paymentStatus, orderId, result.reservation)
            processed(request.orderId)
        } else {
            skipped(request.orderId)
        }
    }

    /**
     * DB 전이 성공(`processed = true`) 이후 결제 상태에 따라 후처리를 위임한다.
     *
     * - FAIL    → [StockReleaseService.release] : 재고 +1, claimed SREM, 멱등 키 DEL, ORDER_HOLD DEL, SSE ORDER_FAILED
     * - SUCCESS → [TicketService.issue] : tickets 행 INSERT + SSE TICKET_ISSUED(ticketCode 포함)
     *           → [PaymentConfirmRedisCleanupService.cleanup] : claimed SREM, 멱등 키 COMPLETED, ORDER_HOLD DEL
     *
     * [TicketService.issue] 의 DB INSERT 실패는 예외로 전파된다.
     * Redis 후처리 실패는 각 서비스 내부에서 soft-fail 처리되므로 결제 응답에 영향을 주지 않는다.
     */
    private fun dispatchRedisCleanup(
        paymentStatus: MockPaymentStatus,
        orderId: UUID,
        reservation: com.develop.snaptix.domain.payment.repository.PaymentReservation,
    ) {
        when (paymentStatus) {
            MockPaymentStatus.FAIL ->
                stockReleaseService.release(
                    orderId = reservation.orderId,
                    zoneId = reservation.zoneId,
                    reason = ReleaseReason.PAYMENT_FAILED,
                )

            MockPaymentStatus.SUCCESS -> {
                ticketService.issue(
                    reservationId = reservation.id,
                    orderId = orderId,
                )
                paymentConfirmRedisCleanupService.cleanup(
                    orderId = orderId,
                    zoneId = reservation.zoneId,
                    userId = reservation.userId,
                    internalEventId = reservation.eventId,
                )
            }
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
            throw BusinessException(
                errorCode = ErrorCode.VALIDATION_FAILED,
                fieldErrors = fieldErrors,
            )
        }
    }

    private fun parseOrderId(orderId: String): UUID = try {
        UUID.fromString(orderId)
    } catch (exception: IllegalArgumentException) {
        throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "orderId는 올바른 UUID 형식이어야 합니다.", exception)
    }

    private fun isAlreadyProcessed(orderId: UUID): Boolean = try {
        webhookGuardRedisGateway.isProcessed(orderId)
    } catch (exception: RedisUnavailableException) {
        logger.warn("Webhook processed key lookup failed. orderId={}", orderId, exception)
        false
    } catch (exception: DataAccessException) {
        logger.warn("Webhook processed key lookup failed. orderId={}", orderId, exception)
        false
    }

    private fun markProcessed(orderId: UUID) {
        try {
            webhookGuardRedisGateway.markProcessed(orderId)
        } catch (exception: RedisUnavailableException) {
            logger.warn("Webhook processed key registration failed after DB update. orderId={}", orderId, exception)
        } catch (exception: DataAccessException) {
            logger.warn("Webhook processed key registration failed after DB update. orderId={}", orderId, exception)
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
        private val logger = LoggerFactory.getLogger(MockPaymentWebhookService::class.java)
        private const val MESSAGE_PROCESSED = "결제 결과가 처리되었습니다."
        private const val MESSAGE_SKIPPED = "이미 처리된 결제 결과입니다."
    }
}
