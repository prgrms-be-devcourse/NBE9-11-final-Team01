package com.develop.snaptix.global.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 서킷 복구 재구축(Rebuild) 메트릭 레코더. (모니터링 명세서 §5.3 · §6.3)
 *
 * RebuildService 는 반환 DTO 가 없어 서비스 내부에서 직접 호출한다(§4.4 예외).
 * 결과는 `outcome`(completed/failed/skipped) 라벨로 단일 카운터에 집계한다.
 * outcome 3값을 부트 시 미리 등록해 0-초기화한다(§4.3).
 */

@Component
class RebuildMetrics(
    registry: MeterRegistry,
) {
    private val completed: Counter = total(registry, "completed")
    private val failed: Counter = total(registry, "failed")
    private val skipped: Counter = total(registry, "skipped")

    private val duration: Timer =
        Timer
            .builder("snaptix.rebuild.duration")
            .description("재구축 1회 실행 시간")
            .register(registry)

    private val eventsSummary: DistributionSummary =
        DistributionSummary
            .builder("snaptix.rebuild.events")
            .description("재구축 1회당 처리 이벤트 수")
            .register(registry)

    private val zonesSummary: DistributionSummary =
        DistributionSummary
            .builder("snaptix.rebuild.zones")
            .description("재구축 1회당 처리 zone 수")
            .register(registry)

    fun recordCompleted(
        events: Int,
        zones: Int,
        durationNanos: Long,
    ) {
        completed.increment()
        eventsSummary.record(events.toDouble())
        zonesSummary.record(zones.toDouble())
        duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }

    fun recordFailed(durationNanos: Long) {
        failed.increment()
        duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }

    /** 락 미획득 no-op. */
    fun recordSkipped() {
        skipped.increment()
    }

    private fun total(
        registry: MeterRegistry,
        outcome: String,
    ): Counter = Counter
        .builder("snaptix.rebuild.total")
        .description("재구축 실행 수(outcome 별)")
        .tag("outcome", outcome)
        .register(registry)
}
