package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.domain.order.api.port.OrderIngestPort
import com.develop.snaptix.domain.order.observability.LogAction
import com.develop.snaptix.domain.order.observability.OrderMdc
import com.develop.snaptix.domain.order.observability.OrderMetrics
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.OwnershipRedisGateway
import com.develop.snaptix.global.redis.gateway.RateLimitRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class OrderIngestService(
    private val orderRateLimiter: RateLimitRedisGateway,
    private val eventCacheGateway: EventCacheRedisGateway,
    private val backpressureGuard: BackpressureGuard,
    private val idempotencyGateway: IdempotencyRedisGateway,
    private val orderStreamGateway: OrderStreamGateway,
    private val ownershipRedisGateway: OwnershipRedisGateway,
    private val meterRegistry: MeterRegistry,
) : OrderIngestPort {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val SECONDS_IN_MINUTE = 60L
    }

    /**
     * 인게스트 파이프라인 진입점.
     *
     * MDC 에 userId/eventId/zoneId 를 주입하여 하위 호출 전반에 걸쳐
     * 구조화 로그 필드가 자동으로 포함되도록 한다.
     * @LogAction(QUEUE_XADD) 는 OrderLoggingAspect 가 가로채어
     * action/result/executionTimeMs 를 로그에 추가한다.
     */
    @LogAction("QUEUE_XADD")
    override fun ingest(
        userId: Long,
        request: OrderRequest,
        ip: String,
    ): OrderAcceptedResponse {
        val eventId =
            try {
                requireNotNull(request.eventId) { "eventId는 필수입니다." }
                UUID.fromString(request.eventId)
            } catch (_: IllegalArgumentException) {
                throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "유효하지 않은 eventId 형식입니다.")
            }
        val zoneId = request.zoneId
        val orderId = UUID.randomUUID()

        // MDC 컨텍스트 설정 — OrderLoggingAspect 및 logback JSON 인코더가 수집
        OrderMdc.set(userId = userId, eventId = eventId, zoneId = zoneId)
        try {
            checkRateLimit(ip)
            val eventInfo = validateEventStatus(eventId)
            val totalCapacity =
                eventInfo.totalCapacity
                    ?: throw BusinessException(
                        ErrorCode.RECONCILE_FAILED,
                        "이벤트 수용 인원 정보가 누락되었습니다. 이벤트 Redis 초기화를 확인해주세요.",
                    )

            backpressureGuard.check(eventId, totalCapacity)
            checkIdempotency(userId, eventId, orderId)

            val message = OrderMessage(orderId = orderId, userId = userId, eventId = eventId, zoneId = zoneId)
            return processOrder(message)
        } finally {
            OrderMdc.clearOrderContext()
        }
    }

    private fun checkRateLimit(ip: String) {
        val limitResult = orderRateLimiter.hit(ip, 5, 20)
        if (!limitResult.allowed) {
            throw BusinessException(
                ErrorCode.RATE_LIMIT_EXCEEDED,
                "요청이 너무 많습니다. ${limitResult.retryAfter?.toHumanReadable() ?: "잠시"} 후 다시 시도해주세요.",
                retryAfter = limitResult.retryAfter,
            )
        }
    }

    private fun validateEventStatus(eventId: UUID): EventInfo {
        val eventInfo =
            eventCacheGateway.get(eventId)
                ?: throw BusinessException(ErrorCode.RECONCILE_FAILED, "이벤트 캐시 정보를 찾을 수 없습니다.")
        if (eventInfo.status != "ON_SALE") {
            throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "현재 판매 중인 이벤트가 아닙니다.")
        }
        return eventInfo
    }

    private fun checkIdempotency(
        userId: Long,
        eventId: UUID,
        orderId: UUID,
    ) {
        val isAcquired = idempotencyGateway.tryAcquire(userId, eventId, orderId)
        if (!isAcquired) {
            log.atWarn {
                message = "Idempotency conflict — duplicate order in flight"
                payload =
                    mapOf(
                        "action" to "IDEMPOTENCY_CHECK",
                        "result" to "CONFLICT",
                        "userId" to userId,
                        "eventId" to eventId,
                    )
            }
            throw BusinessException(ErrorCode.DUPLICATE_ORDER)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processOrder(message: OrderMessage): OrderAcceptedResponse {
        try {
            ownershipRedisGateway.set(message.orderId, message.userId)
            orderStreamGateway.add(message)

            // §9 메트릭: XADD 성공 건수
            meterRegistry.counter(OrderMetrics.QUEUE_SIZE).increment()

            return OrderAcceptedResponse(
                orderId = message.orderId.toString(),
                sseUrl = "/api/v1/orders/sse/${message.orderId}",
                statusUrl = "/api/v1/orders/${message.orderId}",
                message = "주문 요청이 성공적으로 대기열에 접수되었습니다.",
            )
        } catch (e: Exception) {
            log.atError {
                this.message = "Queue push failed — rolling back idempotency and ownership keys"
                cause = e
                payload =
                    mapOf(
                        "action" to "QUEUE_XADD",
                        "result" to "ROLLBACK",
                        "orderId" to message.orderId,
                    )
            }
            idempotencyGateway.compareAndDelete(message.userId, message.eventId, message.orderId)
            ownershipRedisGateway.delete(message.orderId)
            throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 적재 중 오류가 발생했습니다.")
        }
    }

    private fun Duration.toHumanReadable(): String = when {
        this.seconds < SECONDS_IN_MINUTE -> "${this.seconds}초"
        else -> "${this.toMinutes()}분"
    }
}
