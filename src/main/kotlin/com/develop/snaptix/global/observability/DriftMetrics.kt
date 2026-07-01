package com.develop.snaptix.global.observability

import com.develop.snaptix.domain.reservation.service.DriftReport
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 상시 드리프트 정산(Drift) 메트릭 레코더. (모니터링 명세서 §5.2 · §6.1)
 *
 * [DriftReport] 의 fixed/oversell/unchanged/skipped/failed 카운트를 미터로 옮긴다.
 * 미터를 필드로 미리 등록해 0-초기화한다(§4.3).
 */

@Component
class DriftMetrics(
    registry: MeterRegistry,
) {
    private val fixed: Counter =
        Counter
            .builder("snaptix.drift.fixed")
            .description("누수 보정(correctStock) 수")
            .register(registry)

    private val oversell: Counter =
        Counter
            .builder("snaptix.drift.oversell")
            .description("오버셀 감지(알림만) 수")
            .register(registry)

    private val unchanged: Counter =
        Counter
            .builder("snaptix.drift.unchanged")
            .description("기대==실제 무동작 수")
            .register(registry)

    private val skipped: Counter =
        Counter
            .builder("snaptix.drift.skipped")
            .description("stock 키 부재 skip 수")
            .register(registry)

    private val failed: Counter =
        Counter
            .builder("snaptix.drift.failed")
            .description("zone/청크 단위 격리 실패 수")
            .register(registry)

    private val duration: Timer =
        Timer
            .builder("snaptix.drift.duration")
            .description("드리프트 정산 1회 실행 시간")
            .register(registry)

    fun record(
        report: DriftReport,
        durationNanos: Long,
    ) {
        fixed.increment(report.fixed.toDouble())
        oversell.increment(report.oversell.toDouble())
        unchanged.increment(report.unchanged.toDouble())
        skipped.increment(report.skipped.toDouble())
        failed.increment(report.failed.toDouble())
        duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
