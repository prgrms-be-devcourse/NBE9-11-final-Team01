package com.develop.snaptix.global.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 주문 Stream 트림(OrderStreamTrim) 메트릭 레코더. (모니터링 명세서 §5.4 · §6.1)
 *
 * ACK 완료분 트림 결과를 미터로 옮긴다. 미터를 필드로 미리 등록해 0-초기화한다(§4.3).
 *
 * ## 깊이 게이지 (XLEN · XPENDING)
 * 이벤트가 동적으로 추가되므로 `eventId` 태그를 붙인 게이지를 이벤트별로 lazy 등록한다.
 * 내부 [AtomicLong] 맵이 실제 값을 보관하며, 트림 스케줄러 주기마다 [updateDepth]로 갱신된다.
 * - `snaptix.order.stream.xlen`    — 현재 Stream 백로그 깊이 (XLEN)
 * - `snaptix.order.stream.xpending` — 현재 PEL 적체 깊이 (XPENDING)
 */
@Component
class OrderStreamTrimMetrics(
    private val registry: MeterRegistry,
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

    // 이벤트별 동적 깊이 게이지 — eventId 태그를 갖는 게이지를 최초 등록 후 AtomicLong으로 값 갱신
    private val xlenMap = ConcurrentHashMap<String, AtomicLong>()
    private val xpendingMap = ConcurrentHashMap<String, AtomicLong>()

    fun recordTrimmed(
        count: Long,
        durationNanos: Long,
    ) {
        trimmed.increment(count.toDouble())
        duration.record(durationNanos, TimeUnit.NANOSECONDS)
    }

    /**
     * XLEN·XPENDING 깊이 게이지를 이벤트별로 갱신한다.
     *
     * 해당 eventId의 게이지가 아직 등록되지 않았으면 [MeterRegistry]에 등록하고,
     * 이후 호출에서는 [AtomicLong]만 업데이트한다(중복 등록 없음).
     */
    fun updateDepth(
        eventPublicId: UUID,
        xlen: Long,
        xpending: Long,
    ) {
        val eventId = eventPublicId.toString()

        xlenMap
            .computeIfAbsent(eventId) { id ->
                val ref = AtomicLong(0L)
                Gauge
                    .builder("snaptix.order.stream.xlen") { ref.get().toDouble() }
                    .description("현재 Stream 백로그 깊이 (XLEN)")
                    .tag("eventId", id)
                    .register(registry)
                ref
            }.set(xlen)

        xpendingMap
            .computeIfAbsent(eventId) { id ->
                val ref = AtomicLong(0L)
                Gauge
                    .builder("snaptix.order.stream.xpending") { ref.get().toDouble() }
                    .description("현재 PEL 적체 깊이 (XPENDING)")
                    .tag("eventId", id)
                    .register(registry)
                ref
            }.set(xpending)
    }
}
