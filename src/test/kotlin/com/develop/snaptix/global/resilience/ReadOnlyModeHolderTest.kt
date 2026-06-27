package com.develop.snaptix.global.resilience

import com.develop.snaptix.global.resilience.ReadOnlyModeHolder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ReadOnlyModeHolder 단위 테스트. (작업 명세서 v2.1 §7)
 * 인스턴스 로컬 AtomicBoolean 토글만 검증(단일 인스턴스 전제).
 */
class ReadOnlyModeHolderTest {
    private val holder = ReadOnlyModeHolder()

    @Test
    fun `should_기본값_false_when_초기상태면`() {
        assertThat(holder.isReadOnly()).isFalse()
    }

    @Test
    fun `should_true_when_enable하면`() {
        holder.enable()
        assertThat(holder.isReadOnly()).isTrue()
    }

    @Test
    fun `should_다시_false_when_disable하면`() {
        holder.enable()
        holder.disable()
        assertThat(holder.isReadOnly()).isFalse()
    }
}
