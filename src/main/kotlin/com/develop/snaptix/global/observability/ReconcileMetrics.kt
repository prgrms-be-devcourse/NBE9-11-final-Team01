package com.develop.snaptix.global.observability

import com.develop.snaptix.domain.reservation.service.ReconcileReport
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 만료 정산(Reconcile) 메트릭 레코더. (모니터링 명세서 §5.1 · §6.2)
 *
 * [ReconcileReport] 의 카운트를 Micrometer 미터로 옮긴다(비즈니스 로직 무변경).
 * `trigger` 라벨로 스케줄러/Admin 경로를 구분한다(저카디널리티 — §4.2).
 * 두 trigger 값을 부트 시 미리 등록해 0-초기화한다(§4.3: 부재 vs 0 구분).
 */

@Component
class ReconcileMetrics(
    registry: MeterRegistry,
) {
    enum class Trigger { SCHEDULED, ADMIN }

    private class Meters(
        val released: Counter,
        val compensated: Counter,
        val failed: Counter,
        val duration: Timer,
    )

    private val metersByTrigger: Map<Trigger, Meters> =
        Trigger.entries.associateWith { trigger ->
            val tag = trigger.name.lowercase()
            Meters(
                released =
                    Counter
                        .builder("snaptix.reconcile.released")
                        .description("RELEASED 처리된 만료 예약 수")
                        .tag("trigger", tag)
                        .register(registry),
                compensated =
                    Counter
                        .builder("snaptix.reconcile.compensated")
                        .description("재고 보상(+1)이 실제 수행된 수")
                        .tag("trigger", tag)
                        .register(registry),
                failed =
                    Counter
                        .builder("snaptix.reconcile.failed")
                        .description("행 단위 정산 실패 수")
                        .tag("trigger", tag)
                        .register(registry),
                duration =
                    Timer
                        .builder("snaptix.reconcile.duration")
                        .description("정산 1회 실행 시간")
                        .tag("trigger", tag)
                        .register(registry),
            )
        }

    fun record(
        report: ReconcileReport,
        durationNanos: Long,
        trigger: Trigger,
    ) {
        val meters = metersByTrigger.getValue(trigger)
        meters.released.increment(report.released.toDouble())
        meters.compensated.increment(report.compensated.toDouble())
        meters.failed.increment(report.failed.toDouble())
        meters.duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
