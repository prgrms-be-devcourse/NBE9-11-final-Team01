package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.domain.order.api.port.OrderIngestPort
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.OwnershipRedisGateway
import com.develop.snaptix.global.redis.gateway.RateLimitRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import io.github.oshai.kotlinlogging.KotlinLogging
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
) : OrderIngestPort {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val SECONDS_IN_MINUTE = 60L
    }

    override fun ingest(
        userId: Long,
        request: OrderRequest,
        ip: String,
    ): OrderAcceptedResponse {
        val eventId = UUID.fromString(request.eventId)
        val zoneId = request.zoneId
        val orderId = UUID.randomUUID()

        checkRateLimit(ip)
        val eventInfo = validateEventStatus(eventId)
        val totalCapacity =
            eventInfo.totalCapacity
                ?: throw BusinessException(ErrorCode.RECONCILE_FAILED, "이벤트 수용 인원 정보가 누락되었습니다. 이벤트 Redis 초기화를 확인해주세요.")

        backpressureGuard.check(eventId, totalCapacity)
        checkIdempotency(userId, eventId, orderId)

        return processOrder(userId, eventId, zoneId, orderId)
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
            log.warn { "[IDEMPOTENCY_CONFLICT] 이미 주문이 진행 중입니다. userId=$userId, eventId=$eventId" }
            throw BusinessException(ErrorCode.DUPLICATE_ORDER)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processOrder(
        userId: Long,
        eventId: UUID,
        zoneId: Long,
        orderId: UUID,
    ): OrderAcceptedResponse {
        try {
            ownershipRedisGateway.set(orderId, userId)

            val payload =
                mapOf(
                    "orderId" to orderId.toString(),
                    "userId" to userId.toString(),
                    "eventId" to eventId.toString(),
                    "zoneId" to zoneId.toString(),
                )
            orderStreamGateway.add(eventId, payload)

            return OrderAcceptedResponse(
                orderId = orderId.toString(),
                sseUrl = "/api/v1/orders/sse/$orderId",
                statusUrl = "/api/v1/orders/$orderId",
                message = "주문 요청이 성공적으로 대기열에 접수되었습니다.",
            )
        } catch (e: Exception) {
            log.error(e) { "[INGEST_FAILED] 큐 적재 실패로 인한 롤백 수행. orderId=$orderId" }
            idempotencyGateway.compareAndDelete(userId, eventId, orderId)
            ownershipRedisGateway.delete(orderId)
            throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 적재 중 오류가 발생했습니다.")
        }
    }

    private fun Duration.toHumanReadable(): String = when {
        this.seconds < SECONDS_IN_MINUTE -> "${this.seconds}초"
        else -> "${this.toMinutes()}분"
    }
}
