package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.observability.OrderMetrics
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class BackpressureGuard(
    private val orderStreamGateway: OrderStreamGateway,
    private val meterRegistry: MeterRegistry,
) {
    private val log = KotlinLogging.logger {}

    companion object {
        // 인게스트 허용량 버퍼 비율 (예: 정원의 120%까지 대기열 적재 허용)
        private const val BUFFER_RATIO = 1.2
    }

    /**
     * 현재 큐의 길이(XLEN)를 검사하여 임계치를 초과할 경우 429 예외를 발생시킨다.
     */
    fun check(
        eventPublicId: UUID,
        totalCapacity: Int,
    ) {
        val currentLength = orderStreamGateway.length(eventPublicId)
        val threshold = (totalCapacity * BUFFER_RATIO).toLong()

        if (currentLength >= threshold) {
            log.warn {
                "[BACKPRESSURE_GUARD] 큐 포화 상태로 인한 요청 차단. " +
                    "eventId=$eventPublicId, currentLength=$currentLength, threshold=$threshold"
            }

            // 프로메테우스/그라파나 모니터링을 위한 백프레셔 발동 메트릭 증가
            meterRegistry.counter(OrderMetrics.BACKPRESSURE_COUNT).increment()

            // 429 Too Many Requests 응답 유도
            throw BusinessException(ErrorCode.QUEUE_CAPACITY_EXCEEDED)
        }
    }
}
