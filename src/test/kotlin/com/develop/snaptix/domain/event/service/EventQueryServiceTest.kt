package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.repository.EventDetail
import com.develop.snaptix.domain.event.repository.EventDetailQueryResult
import com.develop.snaptix.domain.event.repository.EventDetailZoneRecord
import com.develop.snaptix.domain.event.repository.EventListEventRecord
import com.develop.snaptix.domain.event.repository.EventListPageRecord
import com.develop.snaptix.domain.event.repository.EventListSearchCondition
import com.develop.snaptix.domain.event.repository.EventListSortBy
import com.develop.snaptix.domain.event.repository.EventListSortDir
import com.develop.snaptix.domain.event.repository.EventListZoneRecord
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.domain.zone.dto.ZoneWithEventId
import com.develop.snaptix.domain.zone.repository.ZoneRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EventQueryServiceTest {
    private val eventRepository = mockk<EventRepository>()
    private val zoneRepository = mockk<ZoneRepository>()
    private val reservationRepository = mockk<ReservationRepository>()
    private val valueOperations = mockk<ValueOperations<String, String>>()
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val eventCacheRedisGateway = mockk<EventCacheRedisGateway>()
    private val stockRedisGateway = mockk<StockRedisGateway>()
    private val eventQueryService =
        EventQueryService(
            eventRepository = eventRepository,
            reservationRepository = reservationRepository,
            eventCacheRedisGateway = eventCacheRedisGateway,
            stockRedisGateway = stockRedisGateway,
            reconcileProperties = ReconcileProperties(),
            zoneRepository =
            zoneRepository,
        )

    @Test
    fun `시작일이 종료일보다 이후이면 예외가 발생한다`() {
        assertThatThrownBy {
            eventQueryService.getEvents(
                EventListRequest(
                    startDate = LocalDate.parse("2027-12-31"),
                    endDate = LocalDate.parse("2027-12-01"),
                ),
            )
        }.isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER)
    }

    @Test
    fun `정렬 조건과 KST 날짜 경계를 조회 조건으로 변환한다`() {
        val conditionSlot = slot<EventListSearchCondition>()
        every { eventRepository.findPublicEventPage(capture(conditionSlot)) } returns emptyPage()

        val response =
            eventQueryService.getEvents(
                EventListRequest(
                    page = 2,
                    size = 10,
                    sortBy = "createdAt",
                    sortDir = "desc",
                    location = "서울",
                    startDate = LocalDate.parse("2027-12-26"),
                    endDate = LocalDate.parse("2027-12-28"),
                ),
            )

        assertThat(response.pageable.pageNumber).isEqualTo(2)
        assertThat(response.pageable.pageSize).isEqualTo(10)
        assertThat(conditionSlot.captured.sortBy).isEqualTo(EventListSortBy.CREATED_AT)
        assertThat(conditionSlot.captured.sortDir).isEqualTo(EventListSortDir.DESC)
        assertThat(conditionSlot.captured.location).isEqualTo("서울")
        assertThat(conditionSlot.captured.startTimeFrom).isEqualTo(Instant.parse("2027-12-25T15:00:00Z"))
        assertThat(conditionSlot.captured.startTimeBefore).isEqualTo(Instant.parse("2027-12-28T15:00:00Z"))
    }

    @Test
    fun `허용되지 않는 sortBy이면 예외가 발생한다`() {
        assertInvalidParameter {
            eventQueryService.getEvents(EventListRequest(sortBy = "id"))
        }
    }

    @Test
    fun `허용되지 않는 sortDir이면 예외가 발생한다`() {
        assertInvalidParameter {
            eventQueryService.getEvents(EventListRequest(sortDir = "latest"))
        }
    }

    @Test
    fun `구역 가격 중 최소 가격과 매진 여부를 계산한다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones =
                    listOf(
                        zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000),
                        zoneRecord(eventId = 1L, zoneId = 11L, unitPrice = 90_000),
                    ),
                totalElements = 1L,
            )
        every { redisTemplate.opsForValue() } returns valueOperations
        every { stockRedisGateway.get(10L) } returns 0
        every { stockRedisGateway.get(11L) } returns 0

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content).hasSize(1)
        assertThat(response.content[0].minPrice).isEqualTo(90_000)
        assertThat(response.content[0].isSoldOut).isTrue()
    }

    @Test
    fun `구역 중 일부라도 재고가 있으면 매진이 아니다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones =
                    listOf(
                        zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000),
                        zoneRecord(eventId = 1L, zoneId = 11L, unitPrice = 90_000),
                    ),
                totalElements = 1L,
            )
        every { redisTemplate.opsForValue() } returns valueOperations
        every { stockRedisGateway.get(10L) } returns 5
        every { stockRedisGateway.get(11L) } returns 0

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content[0].isSoldOut).isFalse()
    }

    @Test
    fun `구역이 없으면 최소 가격은 0이고 매진이 아니다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones = emptyList(),
                totalElements = 1L,
            )

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content[0].minPrice).isZero()
        assertThat(response.content[0].isSoldOut).isFalse()
    }

    @Test
    fun `Redis 재고 조회 실패 시 DB 폴백으로 매진 여부를 판단한다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones = listOf(zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000)),
                totalElements = 1L,
            )
        every { stockRedisGateway.get(10L) } throws RedisConnectionFailureException("redis down")
        every { zoneRepository.findWithEventIdById(10L) } returns
            ZoneWithEventId(id = 10L, eventId = 1L, totalCapacity = 100)
        every {
            reservationRepository.countOccupiedByZone(
                eventId = 1L,
                holdCutoff = any(),
            )
        } returns mapOf(10L to 100) // 전석 점유 → 매진

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content[0].isSoldOut).isTrue()
    }

    @Test
    fun `Redis 재고 조회 실패 시 DB 폴백으로 잔여석이 있으면 매진이 아니다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones = listOf(zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000)),
                totalElements = 1L,
            )
        every { stockRedisGateway.get(10L) } throws RedisConnectionFailureException("redis down")
        every { zoneRepository.findWithEventIdById(10L) } returns
            ZoneWithEventId(id = 10L, eventId = 1L, totalCapacity = 100)
        every {
            reservationRepository.countOccupiedByZone(
                eventId = 1L,
                holdCutoff = any(),
            )
        } returns mapOf(10L to 50) // 50석 잔여 → 매진 아님

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content[0].isSoldOut).isFalse()
    }

    @Test
    fun `전체 개수가 0이면 전체 페이지도 0이다`() {
        every { eventRepository.findPublicEventPage(any()) } returns emptyPage(totalElements = 0L)

        val response = eventQueryService.getEvents(EventListRequest(size = 20))

        assertThat(response.pageable.totalElements).isZero()
        assertThat(response.pageable.totalPages).isZero()
    }

    @Test
    fun `전체 페이지는 올림으로 계산한다`() {
        every { eventRepository.findPublicEventPage(any()) } returns emptyPage(totalElements = 45L)

        val response = eventQueryService.getEvents(EventListRequest(size = 20))

        assertThat(response.pageable.totalElements).isEqualTo(45L)
        assertThat(response.pageable.totalPages).isEqualTo(3)
    }

    @Test
    fun `기본 정렬 조건은 시작 시간 오름차순이다`() {
        val conditionSlot = slot<EventListSearchCondition>()
        every { eventRepository.findPublicEventPage(capture(conditionSlot)) } returns emptyPage()

        eventQueryService.getEvents(EventListRequest())

        assertThat(conditionSlot.captured.sortBy).isEqualTo(EventListSortBy.START_TIME)
        assertThat(conditionSlot.captured.sortDir).isEqualTo(EventListSortDir.ASC)
    }

    @Test
    fun `상세 조회에서 모든 Redis 재고가 있으면 MySQL fallback을 조회하지 않는다`() {
        val eventPublicId = UUID.randomUUID().toString()
        val zones =
            listOf(
                detailZoneRecord(id = 10L, totalCapacity = 100),
                detailZoneRecord(id = 11L, totalCapacity = 200),
            )
        every { eventCacheRedisGateway.get(UUID.fromString(eventPublicId)) } returns null
        every { eventCacheRedisGateway.put(UUID.fromString(eventPublicId), any()) } returns Unit
        every { eventRepository.findEventDetailByPublicId(eventPublicId) } returns
            EventDetailQueryResult(
                event = eventDetail(publicId = eventPublicId),
                zones = zones,
            )
        every { stockRedisGateway.getAll(listOf(10L, 11L)) } returns mapOf(10L to 57, 11L to 0)

        val response = eventQueryService.getEventDetail(eventPublicId)

        assertThat(response.zones.map { it.currentStock }).containsExactly(57, 0)
        verify(exactly = 0) { reservationRepository.countOccupiedByZone(any(), any()) }
    }

    private fun assertInvalidParameter(action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER)
    }

    private fun emptyPage(totalElements: Long = 0L): EventListPageRecord = EventListPageRecord(
        events = emptyList(),
        zones = emptyList(),
        totalElements = totalElements,
    )

    private fun eventRecord(id: Long): EventListEventRecord = EventListEventRecord(
        id = id,
        publicId = "event-public-id-$id",
        name = "이벤트 $id",
        location = "서울",
        startTime = Instant.parse("2027-12-25T10:00:00Z"),
        posterUrl = "https://cdn.snaptix.kr/events/test.jpg",
        status = EventStatus.ON_SALE,
    )

    private fun zoneRecord(
        eventId: Long,
        zoneId: Long,
        unitPrice: Int,
    ): EventListZoneRecord = EventListZoneRecord(
        eventId = eventId,
        zoneId = zoneId,
        unitPrice = unitPrice,
    )

    private fun eventDetail(
        id: Long = 1L,
        publicId: String = UUID.randomUUID().toString(),
    ): EventDetail = EventDetail(
        id = id,
        publicId = publicId,
        name = "이벤트 상세",
        description = "상세 설명",
        location = "서울",
        startTime = Instant.parse("2027-12-25T10:00:00Z"),
        endTime = Instant.parse("2027-12-25T13:00:00Z"),
        posterUrl = "https://cdn.snaptix.kr/events/detail.jpg",
        status = EventStatus.ON_SALE.name,
    )

    private fun detailZoneRecord(
        id: Long,
        totalCapacity: Int,
    ): EventDetailZoneRecord = EventDetailZoneRecord(
        id = id,
        publicId = UUID.randomUUID().toString(),
        name = "구역 $id",
        unitPrice = 100_000,
        totalCapacity = totalCapacity,
    )
}
