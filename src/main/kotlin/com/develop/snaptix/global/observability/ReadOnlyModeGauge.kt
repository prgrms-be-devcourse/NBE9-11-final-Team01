package com.develop.snaptix.global.observability

import com.develop.snaptix.global.resilience.ReadOnlyModeHolder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Read-Only 모드 게이지. (모니터링 명세서 §5.5 · §6.1)
 *
 * 재구축 중 ON 되는 [ReadOnlyModeHolder] 상태를 1(ON)/0(OFF) 게이지로 노출한다.
 * 결선이 아니라 생성 시 1회 바인딩이며, 게이지는 홀더를 폴링한다.
 * 홀더는 싱글톤 빈이지만 GC 안전을 위해 strongReference 로 둔다.
 */

@Component
class ReadOnlyModeGauge(
    registry: MeterRegistry,
    holder: ReadOnlyModeHolder,
) {
    init {
        Gauge
            .builder("snaptix.readonly.mode", holder) { if (it.isReadOnly()) 1.0 else 0.0 }
            .description("재구축 중 Read-Only 모드 (1=ON, 0=OFF)")
            .strongReference(true)
            .register(registry)
    }
}
