package com.develop.snaptix.global.observability

import com.develop.snaptix.global.resilience.ReadOnlyModeHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ReadOnlyModeGauge 단위 테스트. 홀더는 단순 AtomicBoolean 래퍼라 실제 인스턴스 사용.
 */
class ReadOnlyModeGaugeTest {
    private val registry = SimpleMeterRegistry()
    private val holder = ReadOnlyModeHolder()

    init {
        // 생성 시 게이지가 레지스트리에 바인딩된다(strongReference 로 holder 폴링).
        ReadOnlyModeGauge(registry, holder)
    }

    @Test
    fun `Read-Only OFF면 게이지는 0이다`() {
        assertThat(gaugeValue()).isEqualTo(0.0)
    }

    @Test
    fun `enable하면 게이지는 1이 된다`() {
        holder.enable()

        assertThat(gaugeValue()).isEqualTo(1.0)
    }

    @Test
    fun `disable하면 게이지는 다시 0이 된다`() {
        holder.enable()
        holder.disable()

        assertThat(gaugeValue()).isEqualTo(0.0)
    }

    private fun gaugeValue(): Double = registry.get("snaptix.readonly.mode").gauge().value()
}
