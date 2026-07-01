package com.develop.snaptix.global.observability

import com.develop.snaptix.domain.event.service.CleanupReport
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 고아 키 정리 스윕(EventKeyCleanup) 메트릭 레코더. (모니터링 명세서 §5.4 · §6.1)
 *
 * [CleanupReport] 의 cleaned/skipped/failed 카운트를 미터로 옮긴다.
 * 미터를 필드로 미리 등록해 0-초기화한다(§4.3).
 */

@Component
class CleanupMetrics(
    registry: MeterRegistry,
) {
    private val cleaned: Counter =
        Counter
            .builder("snaptix.eventkey.cleanup.cleaned")
            .description("실제 키 삭제가 일어난 이벤트 수")
            .register(registry)

    private val skipped: Counter =
        Counter
            .builder("snaptix.eventkey.cleanup.skipped")
            .description("정리할 키가 없던(멱등) 이벤트 수")
            .register(registry)

    private val failed: Counter =
        Counter
            .builder("snaptix.eventkey.cleanup.failed")
            .description("이벤트 단위 격리 실패 수")
            .register(registry)

    private val duration: Timer =
        Timer
            .builder("snaptix.eventkey.cleanup.duration")
            .description("스윕 1회 실행 시간")
            .register(registry)

    fun record(
        report: CleanupReport,
        durationNanos: Long,
    ) {
        cleaned.increment(report.cleaned.toDouble())
        skipped.increment(report.skipped.toDouble())
        failed.increment(report.failed.toDouble())
        duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
