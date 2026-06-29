package com.develop.snaptix.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * RebuildMetrics 단위 테스트. outcome 라벨별 집계 + summary 검증.
 */
class RebuildMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = RebuildMetrics(registry)

    @Test
    fun `부트 직후 outcome 3값이 0으로 미리 등록된다`() {
        assertThat(total("completed")).isZero()
        assertThat(total("failed")).isZero()
        assertThat(total("skipped")).isZero()
    }

    @Test
    fun `recordCompleted는 completed 카운트와 events_zones summary_타이머를 기록한다`() {
        metrics.recordCompleted(events = 2, zones = 5, durationNanos = 1_000_000L)

        assertThat(total("completed")).isEqualTo(1.0)
        assertThat(registry.get("snaptix.rebuild.events").summary().totalAmount()).isEqualTo(2.0)
        assertThat(registry.get("snaptix.rebuild.zones").summary().totalAmount()).isEqualTo(5.0)
        assertThat(registry.get("snaptix.rebuild.duration").timer().count()).isEqualTo(1L)
    }

    @Test
    fun `recordFailed는 failed 카운트를 올린다`() {
        metrics.recordFailed(durationNanos = 1_000_000L)

        assertThat(total("failed")).isEqualTo(1.0)
        assertThat(total("completed")).isZero()
    }

    @Test
    fun `recordSkipped는 skipped 카운트만 올린다`() {
        metrics.recordSkipped()

        assertThat(total("skipped")).isEqualTo(1.0)
        assertThat(registry.get("snaptix.rebuild.duration").timer().count()).isEqualTo(0L)
    }

    private fun total(outcome: String): Double = registry
        .get("snaptix.rebuild.total")
        .tag("outcome", outcome)
        .counter()
        .count()
}
