package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.config.EventCleanupProperties
import com.develop.snaptix.domain.event.repository.EventCleanupCandidate
import com.develop.snaptix.domain.event.repository.EventKeyCleanupRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * EventKeyCleanupService 단위 테스트 (MockK).
 *  - cutoff = now − window 로 repository 호출(위임 정확성)
 *  - 한 이벤트 cleanup 실패가 배치를 막지 않고 failed 집계 + 나머지 계속(격리)
 *  (cleaned/skipped 의 실제 키 정리는 통합 테스트가 담당)
 */
class EventKeyCleanupServiceUnitTest {
    private val repository = mockk<EventKeyCleanupRepository>()
    private val cleaner = mockk<EventRedisKeyCleaner>()
    private val properties =
        EventCleanupProperties().apply { window = Duration.ofDays(1) }
    private val service = EventKeyCleanupService(repository, cleaner, properties)

    private val now = Instant.parse("2026-06-25T03:00:00Z")

    @Test
    fun `should_now에서_window_뺀_cutoff로_조회_when_sweep하면`() {
        every { repository.findClosedCleanupTargets(any()) } returns emptyList()

        service.sweep(now)

        verify(exactly = 1) { repository.findClosedCleanupTargets(now.minus(Duration.ofDays(1))) }
    }

    @Test
    fun `should_한_이벤트_실패를_격리하고_다음_이벤트_계속_when_cleanup중_예외가_나면`() {
        val failing = EventCleanupCandidate(eventPublicId = "evt-fail", zoneIds = listOf(1L))
        val ok = EventCleanupCandidate(eventPublicId = "evt-ok", zoneIds = listOf(2L))
        every { repository.findClosedCleanupTargets(any()) } returns listOf(failing, ok)
        every { cleaner.cleanup(match { it.eventPublicId == "evt-fail" }) } throws RuntimeException("redis error")
        every { cleaner.cleanup(match { it.eventPublicId == "evt-ok" }) } returns 3L

        val report = service.sweep(now)

        // 실패 격리: 다음 이벤트는 계속 처리
        verify(exactly = 1) { cleaner.cleanup(match<EventRedisCleanupTarget> { it.eventPublicId == "evt-ok" }) }
        assertThat(report.cleaned).isEqualTo(1) // evt-ok
        assertThat(report.failed).isEqualTo(1) // evt-fail
        assertThat(report.skipped).isEqualTo(0)
    }

    @Test
    fun `should_skipped_집계_when_삭제할_키가_없으면`() {
        val candidate = EventCleanupCandidate(eventPublicId = "evt-clean", zoneIds = listOf(1L))
        every { repository.findClosedCleanupTargets(any()) } returns listOf(candidate)
        every { cleaner.cleanup(any()) } returns 0L // 이미 정리됨

        val report = service.sweep(now)

        assertThat(report.skipped).isEqualTo(1)
        assertThat(report.cleaned).isEqualTo(0)
    }
}
