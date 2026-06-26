package com.develop.snaptix.domain.order.worker

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@Profile("local") // [크리티컬 1번] 프로파일 가드를 통한 #6 프로duction 구현체와의 빈 경합 차단
class StubActiveEventDiscoveryAdapter : ActiveEventDiscoveryPort {
    private val testEventId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    override fun getActiveEvents(): List<UUID> = listOf(testEventId)
}
