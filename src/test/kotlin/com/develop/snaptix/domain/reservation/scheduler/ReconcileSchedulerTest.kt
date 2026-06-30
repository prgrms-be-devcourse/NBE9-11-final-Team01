package com.develop.snaptix.domain.reservation.scheduler

import com.develop.snaptix.domain.reservation.service.ReconcileReport
import com.develop.snaptix.domain.reservation.service.ReconcileService
import com.develop.snaptix.global.observability.ReconcileMetrics
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ReconcileScheduler 단위 테스트.
 *
 * 스케줄러의 책임은 주입된 [Clock]으로 `now`를 고정해 [ReconcileService.reconcileExpired]에 전달하고,
 * 정상 수행 시 메트릭을 기록하며, 예외 발생 시 이를 안전하게 캐치하는지 검증한다.
 */
class ReconcileSchedulerTest {
    private val reconcileService = mockk<ReconcileService>()
    private val reconcileMetrics = mockk<ReconcileMetrics>(relaxed = true)
    private val emptyReport = ReconcileReport(released = 0, compensated = 0, failed = 0)

    @Test
    fun `should_고정된_clock의_now로_reconcileExpired를_호출하고_메트릭을_기록_when_스케줄_트리거되면`() {
        // given: 시계를 한 시점으로 고정
        val fixedInstant = Instant.parse("2026-06-25T03:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val scheduler = ReconcileScheduler(reconcileService, reconcileMetrics, fixedClock)

        every { reconcileService.reconcileExpired(any()) } returns emptyReport

        // when
        scheduler.reconcile()

        // then: 고정 시각이 그대로 서비스로 전달됨
        verify(exactly = 1) { reconcileService.reconcileExpired(fixedInstant) }

        // 메트릭 기록도 호출되었는지 검증 (Trigger.SCHEDULED)
        verify(exactly = 1) {
            reconcileMetrics.record(
                report = emptyReport,
                durationNanos = any(),
                trigger = ReconcileMetrics.Trigger.SCHEDULED,
            )
        }
        confirmVerified(reconcileService)
    }

    @Test
    fun `should_예외가_발생해도_스케줄러는_중단되지_않고_캐치함_when_reconcileExpired_실패시`() {
        // given: 서비스에서 예외가 발생하도록 모킹
        val fixedInstant = Instant.parse("2026-06-25T03:30:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val scheduler = ReconcileScheduler(reconcileService, reconcileMetrics, fixedClock)

        every { reconcileService.reconcileExpired(any()) } throws RuntimeException("Unexpected Reconcile Error")

        // when & then: 예외가 밖으로 던져지지 않고 내부 try-catch에서 처리되어야 함
        scheduler.reconcile()

        verify(exactly = 1) { reconcileService.reconcileExpired(fixedInstant) }

        // 에러 발생 시 메트릭은 기록되지 않아야 함
        verify(exactly = 0) { reconcileMetrics.record(any(), any(), any()) }
        confirmVerified(reconcileService)
    }
}
