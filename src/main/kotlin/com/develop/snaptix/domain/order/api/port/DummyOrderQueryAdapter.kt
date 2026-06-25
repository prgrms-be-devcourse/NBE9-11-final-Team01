package com.develop.snaptix.domain.order.api.port

import com.develop.snaptix.domain.order.api.dto.OrderStatus
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse
import org.springframework.stereotype.Component

/**
 * Issue #1 단계에서 Spring Boot의 구동 및 API 껍데기 테스트를 위해 제공하는 임시 더미 어댑터입니다.
 * 후속 PR(#13)에서 실제 상태 조회 로직이 구현되면 이 빈은 대체되거나 제거됩니다.
 */
@Component
class DummyOrderQueryAdapter : OrderQueryPort {
    override fun getStatus(
        userId: Long,
        orderId: String,
    ): OrderStatusResponse = OrderStatusResponse(
        orderId = orderId,
        status = OrderStatus.PENDING,
        message = "[DUMMY] 현재 주문 처리 대기열에서 워커의 처리를 기다리는 중입니다.",
    )
}
