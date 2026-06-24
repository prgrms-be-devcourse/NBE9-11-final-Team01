package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventDetailResponse
import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.dto.EventSummaryDto
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.repository.EventDetail
import com.develop.snaptix.domain.event.repository.EventDetailQueryResult
import com.develop.snaptix.domain.event.repository.EventDetailZonesResult
import com.develop.snaptix.domain.event.repository.EventListEventRecord
import com.develop.snaptix.domain.event.repository.EventListSearchCondition
import com.develop.snaptix.domain.event.repository.EventListSortBy
import com.develop.snaptix.domain.event.repository.EventListSortDir
import com.develop.snaptix.domain.event.repository.EventListZoneRecord
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.global.common.dto.PageResponse
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val KST_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")

@Service
class EventQueryService(
    private val eventRepository: EventRepository,
    private val eventCacheRedisGateway: EventCacheRedisGateway,
    private val eventStockReader: EventStockReader,
) {
    private val logger = KotlinLogging.logger {}

    fun getEvents(request: EventListRequest): PageResponse<EventSummaryDto> {
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

        val allZoneIds = page.zones.map { it.zoneId }
        val (stockByZoneId, useFallback) = eventStockReader.readStocksWithFallbackFlag(allZoneIds)
        val occupiedByZoneId =
            if (useFallback) {
                eventStockReader.buildFallbackOccupiedMap(page.zones)
            } else {
                emptyMap()
            }

        return PageResponse.of(
            content =
                page.events.map { event ->
                    event.toSummaryDto(
                        zones = zonesByEventId[event.id].orEmpty(),
                        stockByZoneId = stockByZoneId,
                        occupiedByZoneId = occupiedByZoneId,
                    )
                },
            pageNumber = request.page,
            pageSize = request.size,
            totalElements = page.totalElements,
        )
    }

    fun getEventDetail(eventId: String): EventDetailResponse {
        val eventPublicId = parseEventPublicId(eventId)
        val cachedMetadata = findCachedMetadata(eventPublicId)

        if (cachedMetadata != null) {
            val zones =
                eventRepository.findPublicEventDetailZonesByPublicId(eventId)
                    ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
            return cachedMetadata.toDetailResponse(zones)
        }

        val detail =
            eventRepository.findEventDetailByPublicId(eventId)
                ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
        cacheEventMetadata(eventPublicId, detail.event.toEventInfo())

        return detail.toDetailResponse()
    }

    private fun validateDateRange(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "조회 시작일은 종료일보다 이후일 수 없습니다.")
        }
    }

    private fun EventListEventRecord.toSummaryDto(
        zones: List<EventListZoneRecord>,
        stockByZoneId: Map<Long, Int?>,
        occupiedByZoneId: Map<Long, Int>,
    ): EventSummaryDto = EventSummaryDto(
        eventId = publicId,
        name = name,
        location = location,
        startTime = startTime.atZone(KST_ZONE_ID).toOffsetDateTime(),
        posterUrl = posterUrl,
        status = status,
        minPrice = zones.minOfOrNull { it.unitPrice } ?: 0,
        isSoldOut =
            zones.isNotEmpty() &&
                zones.all { zone ->
                    val stock = stockByZoneId[zone.zoneId]
                    if (stock != null) {
                        stock == 0
                    } else {
                        (zone.totalCapacity - occupiedByZoneId.getOrDefault(zone.zoneId, 0))
                            .coerceAtLeast(0) == 0
                    }
                },
    )

    private fun EventDetailQueryResult.toDetailResponse(): EventDetailResponse = EventDetailResponse(
        eventId = event.publicId,
        name = event.name,
        description = event.description,
        location = event.location,
        posterUrl = event.posterUrl,
        startTime = event.startTime.atZone(KST_ZONE_ID).toOffsetDateTime(),
        endTime = event.endTime.atZone(KST_ZONE_ID).toOffsetDateTime(),
        status = EventStatus.valueOf(event.status),
        zones = eventStockReader.readStockInfoList(event.id, zones),
    )

    private fun EventMetadata.toDetailResponse(zones: EventDetailZonesResult): EventDetailResponse =
        EventDetailResponse(
            eventId = eventId,
            name = name,
            description = description,
            location = location,
            posterUrl = posterUrl,
            startTime = startTime.atZone(KST_ZONE_ID).toOffsetDateTime(),
            endTime = endTime.atZone(KST_ZONE_ID).toOffsetDateTime(),
            status = status,
            zones = eventStockReader.readStockInfoList(zones.eventId, zones.zones),
        )

    private fun parseEventPublicId(eventId: String): UUID = runCatching { UUID.fromString(eventId) }
        .getOrElse { throw BusinessException(ErrorCode.EVENT_NOT_FOUND) }

    private fun findCachedMetadata(eventPublicId: UUID): EventMetadata? = try {
        eventCacheRedisGateway.get(eventPublicId)?.toMetadataOrNull()
    } catch (e: RedisUnavailableException) {
        logger.warn(e) { "[EVENT_DETAIL_CACHE_READ_FAILED] eventPublicId=$eventPublicId" }
        null
    } catch (e: DataAccessException) {
        logger.warn(e) { "[EVENT_DETAIL_CACHE_READ_FAILED] eventPublicId=$eventPublicId" }
        null
    }

    private fun cacheEventMetadata(
        eventPublicId: UUID,
        eventInfo: EventInfo,
    ) {
        try {
            eventCacheRedisGateway.put(eventPublicId, eventInfo)
        } catch (e: RedisUnavailableException) {
            logger.warn(e) { "[EVENT_DETAIL_CACHE_WRITE_FAILED] eventPublicId=$eventPublicId" }
        } catch (e: DataAccessException) {
            logger.warn(e) { "[EVENT_DETAIL_CACHE_WRITE_FAILED] eventPublicId=$eventPublicId" }
        }
    }

    private fun EventInfo.toMetadataOrNull(): EventMetadata? = runCatching {
        val status = EventStatus.valueOf(status)
        if (status !in PUBLIC_DETAIL_STATUSES) return null
        EventMetadata(
            eventId = eventId,
            name = name,
            description = description.takeIf { it.isNotBlank() },
            location = location,
            startTime = Instant.parse(startTime),
            endTime = Instant.parse(endTime),
            posterUrl = posterUrl.takeIf { it.isNotBlank() },
            status = status,
        )
    }.getOrNull()

    private fun EventDetail.toEventInfo(): EventInfo = EventInfo(
        eventId = publicId,
        name = name,
        description = description.orEmpty(),
        location = location,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        status = status,
        posterUrl = posterUrl.orEmpty(),
    )

    private fun String.toEventListSortBy(): EventListSortBy = when (this) {
        "startTime" -> EventListSortBy.START_TIME
        "createdAt" -> EventListSortBy.CREATED_AT
        "name" -> EventListSortBy.NAME
        else -> throw BusinessException(
            ErrorCode.INVALID_REQUEST_PARAMETER,
            "sortBy는 startTime, createdAt, name만 허용됩니다.",
        )
    }

    private fun String.toEventListSortDir(): EventListSortDir = when (lowercase()) {
        "asc" -> EventListSortDir.ASC
        "desc" -> EventListSortDir.DESC
        else -> throw BusinessException(
            ErrorCode.INVALID_REQUEST_PARAMETER,
            "sortDir은 asc 또는 desc만 허용됩니다.",
        )
    }

    private fun LocalDate.toKstStartInstant(): Instant = atStartOfDay(KST_ZONE_ID).toInstant()

    private data class EventMetadata(
        val eventId: String,
        val name: String,
        val description: String?,
        val location: String,
        val startTime: Instant,
        val endTime: Instant,
        val posterUrl: String?,
        val status: EventStatus,
    )

    private companion object {
        val PUBLIC_DETAIL_STATUSES = setOf(EventStatus.ON_SALE, EventStatus.SOLD_OUT)
    }
}
