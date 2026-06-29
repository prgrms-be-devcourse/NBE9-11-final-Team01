package com.develop.snaptix.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * OrderStreamTrimMetrics 단위 테스트.
 */
class OrderStreamTrimMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = OrderStreamTrimMetrics(registry)

    @Test
    fun `부트 직후 trimmed 카운터는 0으로 미리 등록된다`() {
        assertThat(registry.get("snaptix.order.stream.trimmed").counter().count()).isZero()
    }

    @Test
    fun `recordTrimmed는 트림 수를 누적하고 타이머를 기록한다`() {
        metrics.recordTrimmed(count = 5L, durationNanos = 1_000_000L)

        assertThat(registry.get("snaptix.order.stream.trimmed").counter().count()).isEqualTo(5.0)
        assertThat(registry.get("snaptix.order.stream.trim.duration").timer().count()).isEqualTo(1L)
    }
}
