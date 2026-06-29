package com.develop.snaptix.global.observability

import com.develop.snaptix.domain.reservation.service.ReconcileReport
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ReconcileMetrics 단위 테스트. SimpleMeterRegistry 로 미터 값을 직접 검증.
 */
class ReconcileMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = ReconcileMetrics(registry)

    @Test
    fun `부트 직후 scheduled 카운터는 0으로 미리 등록된다`() {
        assertThat(counter("snaptix.reconcile.released", "scheduled")).isZero()
        assertThat(counter("snaptix.reconcile.compensated", "scheduled")).isZero()
        assertThat(counter("snaptix.reconcile.failed", "scheduled")).isZero()
    }

    @Test
    fun `record는 trigger별로 카운트를 누적하고 타이머를 1회 기록한다`() {
        metrics.record(
            ReconcileReport(released = 3, compensated = 2, failed = 1),
            durationNanos = 1_000_000L,
            trigger = ReconcileMetrics.Trigger.SCHEDULED,
        )

        assertThat(counter("snaptix.reconcile.released", "scheduled")).isEqualTo(3.0)
        assertThat(counter("snaptix.reconcile.compensated", "scheduled")).isEqualTo(2.0)
        assertThat(counter("snaptix.reconcile.failed", "scheduled")).isEqualTo(1.0)
        assertThat(timerCount("snaptix.reconcile.duration", "scheduled")).isEqualTo(1L)
    }

    @Test
    fun `scheduled와 admin은 서로 다른 시계열로 분리된다`() {
        metrics.record(ReconcileReport(1, 0, 0), 1L, ReconcileMetrics.Trigger.SCHEDULED)
        metrics.record(ReconcileReport(5, 0, 0), 1L, ReconcileMetrics.Trigger.ADMIN)

        assertThat(counter("snaptix.reconcile.released", "scheduled")).isEqualTo(1.0)
        assertThat(counter("snaptix.reconcile.released", "admin")).isEqualTo(5.0)
    }

    private fun counter(
        name: String,
        trigger: String,
    ): Double = registry
        .get(name)
        .tag("trigger", trigger)
        .counter()
        .count()

    private fun timerCount(
        name: String,
        trigger: String,
    ): Long = registry
        .get(name)
        .tag("trigger", trigger)
        .timer()
        .count()
}
