package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local") // [크리티컬 1번] 프로파일 가드 적용
class StubOrderProcessorAdapter : OrderProcessor {
    private val log = KotlinLogging.logger {}

    override fun process(message: OrderMessage) {
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
