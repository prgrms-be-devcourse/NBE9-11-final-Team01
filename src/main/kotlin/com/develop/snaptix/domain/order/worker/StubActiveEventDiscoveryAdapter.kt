package com.develop.snaptix.domain.order.worker

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class StubActiveEventDiscoveryAdapter : ActiveEventDiscoveryPort {
    // 로컬 테스트용 고정 Event ID (Postman이나 Redis CLI로 이 ID에 맞춰 요청을 보내보세요)
    private val testEventId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    override fun getActiveEvents(): List<UUID> {
        // TODO: 추후 Redis Cache(event:info:*) 스캔 또는 DB 조회 로직으로 교체해야 함
        return listOf(testEventId)
    }
}
