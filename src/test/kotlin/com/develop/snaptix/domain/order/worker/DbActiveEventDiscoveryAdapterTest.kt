package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.event.repository.EventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * DbActiveEventDiscoveryAdapter 단위 테스트.
 *
 * 어댑터는 EventRepository#findActiveEventPublicIds() 에 대한
 * 단순 위임(delegation) 구조이므로, mockk 로 의존성을 모킹해
 * 위임 동작과 반환값 전달만 검증한다.
 *
 * DB 조회 로직의 정확성은 EventRepositoryTest(통합 테스트)에서 검증한다.
 */
class DbActiveEventDiscoveryAdapterTest {
    private val eventRepository: EventRepository = mockk()
    private val adapter = DbActiveEventDiscoveryAdapter(eventRepository)

    @Test
    fun `EventRepository#findActiveEventPublicIds의 반환값을 그대로 반환한다`() {
        val expected = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { eventRepository.findActiveEventPublicIds() } returns expected

        val result = adapter.getActiveEvents()

        assertThat(result).isEqualTo(expected)
        verify(exactly = 1) { eventRepository.findActiveEventPublicIds() }
    }

    @Test
    fun `활성 이벤트가 없으면 빈 목록을 반환한다`() {
        every { eventRepository.findActiveEventPublicIds() } returns emptyList()

        val result = adapter.getActiveEvents()

        assertThat(result).isEmpty()
        verify(exactly = 1) { eventRepository.findActiveEventPublicIds() }
    }
}
