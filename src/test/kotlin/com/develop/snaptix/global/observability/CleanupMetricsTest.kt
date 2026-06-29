package com.develop.snaptix.global.observability

import com.develop.snaptix.domain.event.service.CleanupReport
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * CleanupMetrics 단위 테스트.
 */
class CleanupMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = CleanupMetrics(registry)

    @Test
    fun `부트 직후 카운터는 0으로 미리 등록된다`() {
        assertThat(counter("snaptix.eventkey.cleanup.cleaned")).isZero()
        assertThat(counter("snaptix.eventkey.cleanup.skipped")).isZero()
        assertThat(counter("snaptix.eventkey.cleanup.failed")).isZero()
    }

    @Test
    fun `record는 CleanupReport의 각 카운트를 누적하고 타이머를 기록한다`() {
        metrics.record(
            CleanupReport(cleaned = 7, skipped = 2, failed = 1),
            durationNanos = 3_000_000L,
        )

        assertThat(counter("snaptix.eventkey.cleanup.cleaned")).isEqualTo(7.0)
        assertThat(counter("snaptix.eventkey.cleanup.skipped")).isEqualTo(2.0)
        assertThat(counter("snaptix.eventkey.cleanup.failed")).isEqualTo(1.0)
        assertThat(registry.get("snaptix.eventkey.cleanup.duration").timer().count()).isEqualTo(1L)
    }

    private fun counter(name: String): Double = registry.get(name).counter().count()
}
