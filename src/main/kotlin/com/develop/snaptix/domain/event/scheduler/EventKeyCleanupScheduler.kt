package com.develop.snaptix.domain.event.scheduler

import com.develop.snaptix.domain.event.service.EventKeyCleanupService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 고아 Redis 키 정리 스윕 트리거. (명세서: 독립 스케줄 잡)
 *
 * `@Scheduled`(기본 매시 정각)로 [EventKeyCleanupService.sweep]를 호출한다.
 * 진입 시 [clock]으로 `now`를 1회 고정(드리프트/리컨사일 스케줄러와 동일 패턴). 멱등이라 분산 락 불필요.
 */
@Component
class EventKeyCleanupScheduler(
    private val eventKeyCleanupService: EventKeyCleanupService,
    @Qualifier("alertClock") private val clock: Clock,
) {
    @Scheduled(cron = "\${event.cleanup.cron}")
    fun sweep() {
        eventKeyCleanupService.sweep(Instant.now(clock))
    }
}
