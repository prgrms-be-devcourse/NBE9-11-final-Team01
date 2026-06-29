package com.develop.snaptix.global.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 주문 Stream 트림(OrderStreamTrim) 메트릭 레코더. (모니터링 명세서 §5.4 · §6.1)
 *
 * ACK 완료분 트림 결과를 미터로 옮긴다. 미터를 필드로 미리 등록해 0-초기화한다(§4.3).
 * (참고: XLEN/XPENDING/XAUTOCLAIM deleted 등 스트림 적체 메트릭은 §12 PR-E 범위.)
 */

@Component
class OrderStreamTrimMetrics(
    registry: MeterRegistry,
) {
    private val trimmed: Counter =
        Counter
            .builder("snaptix.order.stream.trimmed")
            .description("ACK 완료로 트림된 Stream 메시지 수")
            .register(registry)

    private val duration: Timer =
        Timer
            .builder("snaptix.order.stream.trim.duration")
            .description("트림 1회 실행 시간")
            .register(registry)

    fun recordTrimmed(
        count: Long,
        durationNanos: Long,
    ) {
        trimmed.increment(count.toDouble())
        duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
