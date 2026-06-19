package com.develop.snaptix.global.realtime

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 주기적으로 활성 SSE 연결에 heartbeat(주석 ping)를 보내 죽은 연결을 조기 감지·정리한다.
 * (PR-03, Story 4.1)
 *
 * 주기는 `realtime.sse.heartbeat-interval`(기본 30s). 스케줄링 활성화를 위해
 * 메인 클래스 또는 설정에 `@EnableScheduling` 이 필요하다(기존 정산 배치와 공유).
 */
@Component
class HeartbeatScheduler(
    private val connectionManager: InMemorySseConnectionManager,
) {
    @Scheduled(fixedRateString = "\${realtime.sse.heartbeat-interval:30s}")
    fun ping() {
        connectionManager.heartbeat()
    }
}
