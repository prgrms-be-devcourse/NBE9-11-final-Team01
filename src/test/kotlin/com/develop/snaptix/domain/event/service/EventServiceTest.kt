package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.dto.EventStatusUpdateRequest
import com.develop.snaptix.domain.event.dto.ZoneCreateRequest
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime
import java.util.UUID

/**
 * [EventService] 통합 테스트.
 *
 * ## 전략
 * - Testcontainers MySQL + Redis (IntegrationTestSupport)로 Exposed `transaction { }` 및
 *   Redis `event:info` 캐시(Lua 초기화 스크립트 포함)까지 실제 경로로 검증한다.
 * - `EventService.createEventWithZones` / `updateEventStatus`는 mockk로 대체하기 어려운
 *   Exposed 트랜잭션에 직접 의존하므로, 순수 단위 테스트 대신 실제 빈을 주입받아 사용한다
 *   (OrphanReclaimerTest와 동일한 전략).
 *
 * ## 커버하는 회귀 포인트
 * `updateEventStatus`가 PENDING→ON_SALE / ON_SALE→SOLD_OUT 전이에서 event:info 캐시의
 * status를 갱신하지 않던 버그(부하 테스트에서 모든 주문이 "현재 판매 중인 이벤트가 아닙니다"로
 * 거부된 원인)에 대한 회귀 테스트.
 */
@SpringBootTest
@DisplayName("EventService 통합 테스트")
class EventServiceTest(
    @Autowired private val eventService: EventService,
    @Autowired private val eventCacheGateway: EventCacheRedisGateway,
) : IntegrationTestSupport() {
    private fun createRequest(
        initialStatus: EventStatus = EventStatus.PENDING,
        zoneCapacity: Int = 100,
    ): EventBulkCreateRequest {
        val now = OffsetDateTime.now()
        return EventBulkCreateRequest(
            name = "테스트 이벤트",
            description = "설명",
            location = "장소",
            startTime = now.plusDays(1),
            endTime = now.plusDays(1).plusHours(3),
            initialStatus = initialStatus,
            zones =
                listOf(
                    ZoneCreateRequest(name = "A구역", unitPrice = 10_000, totalCapacity = zoneCapacity),
                ),
        )
    }

    @Nested
    @DisplayName("이벤트 생성")
    inner class CreateEventWithZones {
        @Test
        @DisplayName("이벤트 생성 시 event:info 캐시에 초기 상태가 저장된다")
        fun `caches initial status on create`() {
            val response = eventService.createEventWithZones(createRequest(initialStatus = EventStatus.PENDING))

            val cached = eventCacheGateway.get(UUID.fromString(response.eventId))

            assertThat(cached).isNotNull
            assertThat(cached!!.status).isEqualTo("PENDING")
        }
    }

    @Nested
    @DisplayName("이벤트 상태 변경 — Redis 캐시 동기화 (회귀 테스트)")
    inner class UpdateEventStatusCacheSync {
        @Test
        @DisplayName("PENDING → ON_SALE 전환 시 event:info 캐시의 status도 ON_SALE로 갱신된다")
        fun `syncs cache status to ON_SALE`() {
            val created = eventService.createEventWithZones(createRequest(initialStatus = EventStatus.PENDING))
            val eventId = UUID.fromString(created.eventId)

            eventService.updateEventStatus(created.eventId, EventStatusUpdateRequest(EventStatus.ON_SALE))

            val cached = eventCacheGateway.get(eventId)
            assertThat(cached).isNotNull
            assertThat(cached!!.status).isEqualTo("ON_SALE")
        }

        @Test
        @DisplayName("ON_SALE → SOLD_OUT 전환 시에도 캐시 status가 갱신된다")
        fun `syncs cache status to SOLD_OUT`() {
            val created = eventService.createEventWithZones(createRequest(initialStatus = EventStatus.ON_SALE))
            val eventId = UUID.fromString(created.eventId)

            eventService.updateEventStatus(created.eventId, EventStatusUpdateRequest(EventStatus.SOLD_OUT))

            val cached = eventCacheGateway.get(eventId)
            assertThat(cached).isNotNull
            assertThat(cached!!.status).isEqualTo("SOLD_OUT")
        }

        @Test
        @DisplayName("상태 동기화 후에도 name/totalCapacity 등 나머지 필드는 그대로 유지된다")
        fun `keeps other fields unchanged after sync`() {
            val created =
                eventService.createEventWithZones(
                    createRequest(initialStatus = EventStatus.PENDING, zoneCapacity = 42),
                )
            val eventId = UUID.fromString(created.eventId)
            val before = eventCacheGateway.get(eventId)!!

            eventService.updateEventStatus(created.eventId, EventStatusUpdateRequest(EventStatus.ON_SALE))

            val after = eventCacheGateway.get(eventId)!!
            assertThat(after.name).isEqualTo(before.name)
            assertThat(after.totalCapacity).isEqualTo(before.totalCapacity)
            assertThat(after.totalCapacity).isEqualTo(42)
        }

        @Test
        @DisplayName("CLOSED로 전환하면 캐시를 동기화하는 대신 event:info 키 자체가 삭제된다")
        fun `evicts cache on CLOSED instead of syncing`() {
            val created = eventService.createEventWithZones(createRequest(initialStatus = EventStatus.ON_SALE))
            val eventId = UUID.fromString(created.eventId)

            eventService.updateEventStatus(created.eventId, EventStatusUpdateRequest(EventStatus.CLOSED))

            assertThat(eventCacheGateway.get(eventId)).isNull()
        }

        @Test
        @DisplayName("캐시가 이미 없는 상태(TTL 만료 등)에서 상태를 변경해도 예외 없이 처리된다")
        fun `does nothing when cache already missing during sync`() {
            val created = eventService.createEventWithZones(createRequest(initialStatus = EventStatus.PENDING))
            val eventId = UUID.fromString(created.eventId)
            eventCacheGateway.evict(eventId) // TTL 만료 상황을 시뮬레이션

            eventService.updateEventStatus(created.eventId, EventStatusUpdateRequest(EventStatus.ON_SALE))

            assertThat(eventCacheGateway.get(eventId)).isNull()
        }
    }

    @Nested
    @DisplayName("상태 전이 검증")
    inner class StatusTransitionValidation {
        @Test
        @DisplayName("허용되지 않는 전이(PENDING → CLOSED)는 예외를 던지고 캐시를 건드리지 않는다")
        fun `rejects invalid transition without touching cache`() {
            val created = eventService.createEventWithZones(createRequest(initialStatus = EventStatus.PENDING))
            val eventId = UUID.fromString(created.eventId)

            assertThatThrownBy {
                eventService.updateEventStatus(created.eventId, EventStatusUpdateRequest(EventStatus.CLOSED))
            }.isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER)

            assertThat(eventCacheGateway.get(eventId)!!.status).isEqualTo("PENDING")
        }

        @Test
        @DisplayName("존재하지 않는 eventId면 EVENT_NOT_FOUND를 던진다")
        fun `throws EVENT_NOT_FOUND for unknown eventId`() {
            assertThatThrownBy {
                eventService.updateEventStatus(
                    UUID.randomUUID().toString(),
                    EventStatusUpdateRequest(EventStatus.ON_SALE),
                )
            }.isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND)
        }
    }
}
