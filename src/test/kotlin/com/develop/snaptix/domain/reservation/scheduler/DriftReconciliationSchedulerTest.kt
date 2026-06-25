package com.develop.snaptix.domain.reservation.scheduler

import com.develop.snaptix.domain.reservation.service.DriftReconciliationService
import com.develop.snaptix.domain.reservation.service.DriftReport
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * DriftReconciliationScheduler 단위 테스트.
 *
 * 스케줄러의 책임은 주입된 [Clock]으로 `now`를 1회 고정해 [DriftReconciliationService.checkDrift]에
 * 전달하는 것(계약 #0: 시계 고정). cron 트리거 자체는 Spring이 보장하므로 위임 정확성만 검증한다.
 */

class DriftReconciliationSchedulerTest {
    private val service = mockk<DriftReconciliationService>()
    private val emptyReport = DriftReport(fixed = 0, oversell = 0, unchanged = 0, skipped = 0, failed = 0)

    @Test
    fun `should_고정된_clock의_now로_checkDrift를_호출_when_스케줄_트리거되면`() {
        // given: 시계를 한 시점으로 고정
        val fixedInstant = Instant.parse("2026-06-25T03:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val scheduler = DriftReconciliationScheduler(service, fixedClock)
        every { service.checkDrift(any()) } returns emptyReport

        // when
        scheduler.checkDrift()

        // then: 고정 시각이 그대로 서비스로 전달됨
        verify(exactly = 1) { service.checkDrift(fixedInstant) }
        confirmVerified(service)
    }

    @Test
    fun `should_checkDrift를_정확히_1회만_호출_when_단일_트리거면`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-06-25T00:30:00Z"), ZoneOffset.UTC)
        val scheduler = DriftReconciliationScheduler(service, fixedClock)
        every { service.checkDrift(any()) } returns emptyReport

        scheduler.checkDrift()

        verify(exactly = 1) { service.checkDrift(any()) }
        confirmVerified(service)
    }
}
