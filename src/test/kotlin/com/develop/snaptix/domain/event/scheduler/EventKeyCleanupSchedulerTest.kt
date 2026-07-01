package com.develop.snaptix.domain.event.scheduler

import com.develop.snaptix.domain.event.service.CleanupReport
import com.develop.snaptix.domain.event.service.EventKeyCleanupService
import com.develop.snaptix.global.observability.CleanupMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * EventKeyCleanupScheduler 단위 테스트. 고정 Clock의 now 로 sweep 위임만 검증.
 */
class EventKeyCleanupSchedulerTest {
    private val service = mockk<EventKeyCleanupService>()
    private val cleanupMetrics = mockk<CleanupMetrics>(relaxed = true)

    @Test
    fun `should_고정_clock의_now로_sweep_위임_when_스케줄_트리거되면`() {
        val fixedInstant = Instant.parse("2026-06-25T03:00:00Z")
        val scheduler =
            EventKeyCleanupScheduler(
                service,
                cleanupMetrics,
                Clock.fixed(fixedInstant, ZoneOffset.UTC),
            )
        every { service.sweep(any()) } returns CleanupReport(cleaned = 0, skipped = 0, failed = 0)

        scheduler.sweep()

        verify(exactly = 1) { service.sweep(fixedInstant) }
    }
}
