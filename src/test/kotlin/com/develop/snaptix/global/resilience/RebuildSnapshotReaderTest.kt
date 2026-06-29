package com.develop.snaptix.global.resilience

import com.develop.snaptix.domain.reservation.repository.RebuildSnapshot
import com.develop.snaptix.domain.reservation.repository.RebuildSnapshotRepository
import com.develop.snaptix.global.resilience.config.ReconcileProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * RebuildSnapshotReader 단위 테스트 (MockK).
 *
 * 리팩터 후 reader 의 책임은 `now → holdCutoff` 변환 + repository 위임뿐이므로(산정 로직은
 * RebuildSnapshotRepository → RebuildSnapshotRepositoryTest 가 검증), 여기서는 **cutoff 계산 정확성**만 본다.
 */
class RebuildSnapshotReaderTest {
    private val repository = mockk<RebuildSnapshotRepository>()
    private val properties = ReconcileProperties().apply { holdWindow = Duration.ofMinutes(5) }
    private val reader = RebuildSnapshotReader(repository, properties)

    @Test
    fun `should_now에서_holdWindow_뺀_holdCutoff로_repository호출_when_read하면`() {
        val now = Instant.parse("2026-06-25T03:00:00Z")
        every { repository.read(any()) } returns RebuildSnapshot(emptyList())

        reader.read(now)

        verify(exactly = 1) { repository.read(now.minus(Duration.ofMinutes(5))) }
    }
}
