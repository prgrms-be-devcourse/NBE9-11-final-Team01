package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

@Component
class StubOrderProcessorAdapter : OrderProcessor {
    private val log = KotlinLogging.logger {}

    override fun process(message: OrderMessage) {
        // TODO: 6번 이슈에서 실제 재고 차감, 1인 1매 검증, 영속화 로직으로 교체해야 함
        log.info {
            """
            [STUB_PROCESSOR] 🚀 주문 메시지 소비 성공!
            - OrderId: ${message.orderId}
            - UserId: ${message.userId}
            - EventId: ${message.eventId}
            - ZoneId: ${message.zoneId}
            """.trimIndent()
        }
    }
}
