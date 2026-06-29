package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * EventRepository 통합 테스트 (Testcontainers).
 *
 * findActiveEventPublicIds() 핵심 필터를 직접 검증:
 *  - status == ON_SALE 만 반환
 *  - ON_SALE 이외(CLOSED, SOLD_OUT)는 제외
 *  - 반환값이 UUID로 파싱 가능한 형식
 *
 * NOTE: 테스트 간 DB 격리가 보장되지 않으므로 contains / doesNotContain 으로 검증한다.
 */
class EventRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var eventRepository: EventRepository

    @Test
    fun `ON_SALE 이벤트의 publicId가 UUID 목록으로 반환된다`() {
        val ev1 = EventFixtures.insertEvent(EventStatus.ON_SALE)
        val ev2 = EventFixtures.insertEvent(EventStatus.ON_SALE)

        val result = eventRepository.findActiveEventPublicIds()

        assertThat(result).contains(
            UUID.fromString(ev1.publicId),
            UUID.fromString(ev2.publicId),
        )
    }

    @Test
    fun `ON_SALE 이외 상태(CLOSED, SOLD_OUT)는 결과에 포함되지 않는다`() {
        val closed = EventFixtures.insertEvent(EventStatus.CLOSED)
        val soldOut = EventFixtures.insertEvent(EventStatus.SOLD_OUT)

        val result = eventRepository.findActiveEventPublicIds()

        assertThat(result).doesNotContain(
            UUID.fromString(closed.publicId),
            UUID.fromString(soldOut.publicId),
        )
    }

    @Test
    fun `반환된 publicId는 모두 유효한 UUID 형식이다`() {
        EventFixtures.insertEvent(EventStatus.ON_SALE)

        val result = eventRepository.findActiveEventPublicIds()

        // findActiveEventPublicIds 는 파싱 불가 값을 mapNotNull 로 걸러내므로
        // 반환된 모든 값이 이미 UUID 타입임을 타입 시스템이 보장한다.
        // 추가로, 값 자체가 null 이 아님을 명시적으로 확인한다.
        assertThat(result).allSatisfy { uuid ->
            assertThat(uuid).isNotNull()
            assertThat(uuid.toString()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            )
        }
    }
}
