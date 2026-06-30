package com.develop.snaptix.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

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

    // ── updateDepth — XLEN / XPENDING 깊이 게이지 ───────────────────────────────

    @Test
    fun `updateDepth 호출 시 eventId 태그를 가진 xlen 게이지가 등록된다`() {
        val eventId = UUID.randomUUID()

        metrics.updateDepth(eventId, xlen = 7L, xpending = 3L)

        val gauge =
            registry
                .find("snaptix.order.stream.xlen")
                .tag("eventId", eventId.toString())
                .gauge()
        assertThat(gauge).isNotNull()
        assertThat(gauge!!.value()).isEqualTo(7.0)
    }

    @Test
    fun `updateDepth 호출 시 eventId 태그를 가진 xpending 게이지가 등록된다`() {
        val eventId = UUID.randomUUID()

        metrics.updateDepth(eventId, xlen = 7L, xpending = 3L)

        val gauge =
            registry
                .find("snaptix.order.stream.xpending")
                .tag("eventId", eventId.toString())
                .gauge()
        assertThat(gauge).isNotNull()
        assertThat(gauge!!.value()).isEqualTo(3.0)
    }

    @Test
    fun `updateDepth를 여러 번 호출하면 게이지 값이 최신으로 갱신된다`() {
        val eventId = UUID.randomUUID()

        metrics.updateDepth(eventId, xlen = 10L, xpending = 5L)
        metrics.updateDepth(eventId, xlen = 4L, xpending = 1L)

        val xlenGauge =
            registry
                .find("snaptix.order.stream.xlen")
                .tag("eventId", eventId.toString())
                .gauge()
        val xpendingGauge =
            registry
                .find("snaptix.order.stream.xpending")
                .tag("eventId", eventId.toString())
                .gauge()
        assertThat(xlenGauge!!.value()).isEqualTo(4.0)
        assertThat(xpendingGauge!!.value()).isEqualTo(1.0)
    }

    @Test
    fun `이벤트가 다르면 각자 독립된 게이지를 갖는다`() {
        val eventId1 = UUID.randomUUID()
        val eventId2 = UUID.randomUUID()

        metrics.updateDepth(eventId1, xlen = 10L, xpending = 4L)
        metrics.updateDepth(eventId2, xlen = 3L, xpending = 1L)

        val xlen1 = registry.find("snaptix.order.stream.xlen").tag("eventId", eventId1.toString()).gauge()
        val xlen2 = registry.find("snaptix.order.stream.xlen").tag("eventId", eventId2.toString()).gauge()
        assertThat(xlen1!!.value()).isEqualTo(10.0)
        assertThat(xlen2!!.value()).isEqualTo(3.0)
    }

    @Test
    fun `updateDepth를 반복 호출해도 게이지가 중복 등록되지 않는다`() {
        val eventId = UUID.randomUUID()

        repeat(5) { metrics.updateDepth(eventId, xlen = it.toLong(), xpending = 0L) }

        val xlenGauges =
            registry
                .find("snaptix.order.stream.xlen")
                .tag("eventId", eventId.toString())
                .gauges()
        assertThat(xlenGauges).hasSize(1)
    }
}
