package com.develop.snaptix.global.observability

import com.develop.snaptix.domain.reservation.service.DriftReport
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * DriftMetrics 단위 테스트.
 */
class DriftMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = DriftMetrics(registry)

    @Test
    fun `부트 직후 카운터는 0으로 미리 등록된다`() {
        assertThat(counter("snaptix.drift.fixed")).isZero()
        assertThat(counter("snaptix.drift.oversell")).isZero()
        assertThat(counter("snaptix.drift.failed")).isZero()
    }

    @Test
    fun `record는 DriftReport의 각 카운트를 누적하고 타이머를 기록한다`() {
        metrics.record(
            DriftReport(fixed = 2, oversell = 1, unchanged = 3, skipped = 4, failed = 5),
            durationNanos = 2_000_000L,
        )

        assertThat(counter("snaptix.drift.fixed")).isEqualTo(2.0)
        assertThat(counter("snaptix.drift.oversell")).isEqualTo(1.0)
        assertThat(counter("snaptix.drift.unchanged")).isEqualTo(3.0)
        assertThat(counter("snaptix.drift.skipped")).isEqualTo(4.0)
        assertThat(counter("snaptix.drift.failed")).isEqualTo(5.0)
        assertThat(registry.get("snaptix.drift.duration").timer().count()).isEqualTo(1L)
    }

    private fun counter(name: String): Double = registry.get(name).counter().count()
}
