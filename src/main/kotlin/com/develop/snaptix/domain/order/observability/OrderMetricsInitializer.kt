package com.develop.snaptix.domain.order.observability

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * 부트 시 Order 도메인 카운터 6종을 0으로 선등록한다.
 *
 * Micrometer 카운터는 기본적으로 lazy 등록이므로,
 * 첫 increment 전까지 `/actuator/prometheus`에 노출되지 않는다.
 * 초기화하지 않으면 부트 직후 "미배포 vs 0" 구분이 불가능하여
 * Grafana 대시보드에서 no-data 로 오인될 수 있다.
 *
 * PENDING_SIZE 게이지는 OrphanReclaimer 에서 reclaimOrphans() 호출 시
 * 실측값으로 등록되므로 여기서는 제외한다.
 */
@Component
class OrderMetricsInitializer(
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun init() {
        listOf(
            OrderMetrics.QUEUE_SIZE,
            OrderMetrics.BACKPRESSURE_COUNT,
            OrderMetrics.CLAIM_REPROCESS_COUNT,
            OrderMetrics.DELETED_COUNT,
            OrderMetrics.COMPENSATE_COUNT,
            OrderMetrics.XACK_COUNT,
        ).forEach { name ->
            meterRegistry.counter(name).increment(0.0)
        }
    }
}
