package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.dto.EventListResponse
import com.develop.snaptix.domain.event.dto.EventSummaryDto
import com.develop.snaptix.domain.event.dto.PageMetadataDto
import com.develop.snaptix.domain.event.repository.EventListEventRecord
import com.develop.snaptix.domain.event.repository.EventListSearchCondition
import com.develop.snaptix.domain.event.repository.EventListSortBy
import com.develop.snaptix.domain.event.repository.EventListSortDir
import com.develop.snaptix.domain.event.repository.EventListZoneRecord
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val KST_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")

@Service
class EventQueryService(
    private val eventRepository: EventRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    private val logger = KotlinLogging.logger {}

    fun getEvents(request: EventListRequest): EventListResponse {
        validateDateRange(request.startDate, request.endDate)

        val condition =
            EventListSearchCondition(
                page = request.page,
                size = request.size,
                sortBy = request.sortBy.toEventListSortBy(),
                sortDir = request.sortDir.toEventListSortDir(),
                location = request.location,
                startTimeFrom = request.startDate?.toKstStartInstant(),
                startTimeBefore = request.endDate?.plusDays(1)?.toKstStartInstant(),
            )
        val page = eventRepository.findPublicEventPage(condition)
        val zonesByEventId = page.zones.groupBy { it.eventId }

        return EventListResponse(
            content =
                page.events.map { event ->
                    event.toSummaryDto(zonesByEventId[event.id].orEmpty())
                },
            pageable =
                PageMetadataDto(
                    pageNumber = request.page,
                    pageSize = request.size,
                    totalElements = page.totalElements,
                    totalPages = page.totalElements.toTotalPages(request.size),
                ),
        )
    }

    private fun validateDateRange(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "조회 시작일은 종료일보다 이후일 수 없습니다.")
        }
    }

    private fun EventListEventRecord.toSummaryDto(zones: List<EventListZoneRecord>): EventSummaryDto =
        EventSummaryDto(
            eventId = publicId,
            name = name,
            location = location,
            startTime = startTime.atZone(KST_ZONE_ID).toOffsetDateTime(),
            posterUrl = posterUrl,
            status = status,
            minPrice = zones.minOfOrNull { it.unitPrice } ?: 0,
            isSoldOut = zones.isNotEmpty() && zones.all { it.isSoldOut() },
        )

    private fun EventListZoneRecord.isSoldOut(): Boolean =
        try {
            redisTemplate.opsForValue().get(stockKey(zoneId))?.toIntOrNull() == 0
        } catch (exception: DataAccessException) {
            logger.warn(exception) { "[EVENT_LIST_STOCK_READ_FAILED] zoneId=$zoneId" }
            false
        }

    private fun String.toEventListSortBy(): EventListSortBy =
        when (this) {
            "startTime" -> EventListSortBy.START_TIME
            "createdAt" -> EventListSortBy.CREATED_AT
            "name" -> EventListSortBy.NAME
            else ->
                throw BusinessException(
                    ErrorCode.INVALID_REQUEST_PARAMETER,
                    "sortBy는 startTime, createdAt, name만 허용됩니다.",
                )
        }

    private fun String.toEventListSortDir(): EventListSortDir =
        when (lowercase()) {
            "asc" -> EventListSortDir.ASC
            "desc" -> EventListSortDir.DESC
            else -> throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "sortDir은 asc 또는 desc만 허용됩니다.")
        }

    private fun LocalDate.toKstStartInstant(): Instant = atStartOfDay(KST_ZONE_ID).toInstant()

    private fun Long.toTotalPages(size: Int): Int {
        if (this == 0L) {
            return 0
        }

        return ((this + size - 1) / size).toInt()
    }

    private fun stockKey(zoneId: Long): String = "ZONE:$zoneId:stock"
}
