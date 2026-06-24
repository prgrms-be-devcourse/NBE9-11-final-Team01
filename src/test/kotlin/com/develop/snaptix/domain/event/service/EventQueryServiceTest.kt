package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.dto.ZoneStockInfo
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
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EventQueryServiceTest {
    private val eventRepository = mockk<EventRepository>()
    private val eventCacheRedisGateway = mockk<EventCacheRedisGateway>()
    private val eventStockReader = mockk<EventStockReader>()

    private val eventQueryService =
        EventQueryService(
            eventRepository = eventRepository,
            eventCacheRedisGateway = eventCacheRedisGateway,
            eventStockReader = eventStockReader,
        )

    @BeforeEach
    fun setUp() {
        // 재고 관련 동작은 EventStockReader 테스트에서 검증하므로
        // 여기서는 기본값으로 고정하여 EventQueryService 로직에만 집중
        every { eventStockReader.readStocksWithFallbackFlag(any()) } returns (emptyMap<Long, Int?>() to false)
        every { eventStockReader.buildFallbackOccupiedMap(any()) } returns emptyMap()
    }

    // ────────────────────────────────────────────────
    // getEvents — 입력 검증
    // ────────────────────────────────────────────────

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

    // ────────────────────────────────────────────────
    // getEvents — 조회 조건 변환
    // ────────────────────────────────────────────────

    @Test
    fun `정렬 조건과 KST 날짜 경계를 조회 조건으로 변환한다`() {
        val conditionSlot = slot<EventListSearchCondition>()
        every { eventRepository.findPublicEventPage(capture(conditionSlot)) } returns emptyPage()

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

        assertThat(conditionSlot.captured.page).isEqualTo(2)
        assertThat(conditionSlot.captured.size).isEqualTo(10)
        assertThat(conditionSlot.captured.sortBy).isEqualTo(EventListSortBy.CREATED_AT)
        assertThat(conditionSlot.captured.sortDir).isEqualTo(EventListSortDir.DESC)
        assertThat(conditionSlot.captured.location).isEqualTo("서울")
        assertThat(conditionSlot.captured.startTimeFrom).isEqualTo(Instant.parse("2027-12-25T15:00:00Z"))
        assertThat(conditionSlot.captured.startTimeBefore).isEqualTo(Instant.parse("2027-12-28T15:00:00Z"))
    }

    @Test
    fun `기본 정렬 조건은 시작 시간 오름차순이다`() {
        val conditionSlot = slot<EventListSearchCondition>()
        every { eventRepository.findPublicEventPage(capture(conditionSlot)) } returns emptyPage()

        eventQueryService.getEvents(EventListRequest())

        assertThat(conditionSlot.captured.sortBy).isEqualTo(EventListSortBy.START_TIME)
        assertThat(conditionSlot.captured.sortDir).isEqualTo(EventListSortDir.ASC)
    }

    // ────────────────────────────────────────────────
    // getEvents — 페이지 계산
    // ────────────────────────────────────────────────

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

    // ────────────────────────────────────────────────
    // getEvents — 재고/가격 집계 (EventStockReader 위임 검증)
    // ────────────────────────────────────────────────

    @Test
    fun `구역 가격 중 최소 가격과 매진 여부를 계산한다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones =
                    listOf(
                        zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000, totalCapacity = 100),
                        zoneRecord(eventId = 1L, zoneId = 11L, unitPrice = 90_000, totalCapacity = 100),
                    ),
                totalElements = 1L,
            )
        // 두 zone 모두 재고 0 → 매진
        every { eventStockReader.readStocksWithFallbackFlag(listOf(10L, 11L)) } returns
            (mapOf(10L to 0, 11L to 0) to false)

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
                        zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000, totalCapacity = 100),
                        zoneRecord(eventId = 1L, zoneId = 11L, unitPrice = 90_000, totalCapacity = 100),
                    ),
                totalElements = 1L,
            )
        every { eventStockReader.readStocksWithFallbackFlag(listOf(10L, 11L)) } returns
            (mapOf(10L to 5, 11L to 0) to false)

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
    fun `Redis 장애 시 DB 폴백으로 매진 여부를 판단한다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones = listOf(zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000, totalCapacity = 100)),
                totalElements = 1L,
            )
        // Redis 장애 → useFallback = true
        every { eventStockReader.readStocksWithFallbackFlag(listOf(10L)) } returns
            (emptyMap<Long, Int?>() to true)
        // DB 폴백: 100석 전석 점유 → 매진
        every { eventStockReader.buildFallbackOccupiedMap(any()) } returns mapOf(10L to 100)

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content[0].isSoldOut).isTrue()
        verify(exactly = 1) { eventStockReader.buildFallbackOccupiedMap(any()) }
    }

    @Test
    fun `Redis 장애 시 DB 폴백으로 잔여석이 있으면 매진이 아니다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones = listOf(zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000, totalCapacity = 100)),
                totalElements = 1L,
            )
        every { eventStockReader.readStocksWithFallbackFlag(listOf(10L)) } returns
            (emptyMap<Long, Int?>() to true)
        // DB 폴백: 50석 점유 → 50석 잔여
        every { eventStockReader.buildFallbackOccupiedMap(any()) } returns mapOf(10L to 50)

        val response = eventQueryService.getEvents(EventListRequest())

        assertThat(response.content[0].isSoldOut).isFalse()
    }

    @Test
    fun `Redis 정상일 때는 DB 폴백을 호출하지 않는다`() {
        every { eventRepository.findPublicEventPage(any()) } returns
            EventListPageRecord(
                events = listOf(eventRecord(id = 1L)),
                zones = listOf(zoneRecord(eventId = 1L, zoneId = 10L, unitPrice = 150_000, totalCapacity = 100)),
                totalElements = 1L,
            )
        every { eventStockReader.readStocksWithFallbackFlag(listOf(10L)) } returns
            (mapOf(10L to 0) to false)

        eventQueryService.getEvents(EventListRequest())

        verify(exactly = 0) { eventStockReader.buildFallbackOccupiedMap(any()) }
    }

    // ────────────────────────────────────────────────
    // getEventDetail
    // ────────────────────────────────────────────────

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
        every { eventStockReader.readStockInfoList(1L, zones) } returns
            listOf(
                ZoneStockInfo(
                    zoneId = zones[0].publicId,
                    name = zones[0].name,
                    unitPrice = 100_000,
                    totalCapacity = 100,
                    currentStock = 57,
                ),
                ZoneStockInfo(
                    zoneId = zones[1].publicId,
                    name = zones[1].name,
                    unitPrice = 100_000,
                    totalCapacity = 200,
                    currentStock = 0,
                ),
            )

        val response = eventQueryService.getEventDetail(eventPublicId)

        assertThat(response.zones.map { it.currentStock }).containsExactly(57, 0)
        // DB fallback 호출 여부는 EventStockReaderTest에서 검증
        verify(exactly = 1) { eventStockReader.readStockInfoList(1L, zones) }
    }

    // ────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────

    private fun assertInvalidParameter(action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER)
    }

    private fun emptyPage(totalElements: Long = 0L) = EventListPageRecord(
        events = emptyList(),
        zones = emptyList(),
        totalElements = totalElements,
    )

    private fun eventRecord(id: Long) = EventListEventRecord(
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
        totalCapacity: Int = 100,
    ) = EventListZoneRecord(
        eventId = eventId,
        zoneId = zoneId,
        unitPrice = unitPrice,
        totalCapacity = totalCapacity,
    )

    private fun eventDetail(
        id: Long = 1L,
        publicId: String = UUID.randomUUID().toString(),
    ) = EventDetail(
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
    ) = EventDetailZoneRecord(
        id = id,
        publicId = UUID.randomUUID().toString(),
        name = "구역 $id",
        unitPrice = 100_000,
        totalCapacity = totalCapacity,
    )
}
