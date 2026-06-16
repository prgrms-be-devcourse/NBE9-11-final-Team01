package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventDetailResponse
import com.develop.snaptix.domain.event.dto.EventResponse
import com.develop.snaptix.domain.event.dto.EventStatus
import com.develop.snaptix.domain.event.dto.PageResponse
import com.develop.snaptix.domain.event.dto.PageableMeta
import com.develop.snaptix.domain.event.dto.ZoneStockResponse
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.event.entity.ZonesTable
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    /** Story 11.1: 이벤트 목록 조회 */
    @Suppress("LongParameterList")
    fun getEvents(
        page: Int,
        size: Int,
        sortBy: String,
        sortDir: String,
        location: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): PageResponse<EventResponse> {
        val (eventRows, totalElements) =
            transaction {
                eventRepository.findEventsWithFilters(
                    status = EventStatus.ON_SALE.name,
                    location = location,
                    startDate = startDate,
                    endDate = endDate,
                    page = page,
                    size = size,
                    sortBy = sortBy,
                    sortDir = sortDir,
                )
            }

        if (eventRows.isEmpty()) {
            return createEmptyPageResponse(page, size)
        }

        val eventIds = eventRows.map { it[EventsTable.id] }
        val allZoneRows =
            transaction {
                ZonesTable
                    .selectAll()
                    .where { ZonesTable.eventId inList eventIds }
                    .toList()
            }

        val stockMap = fetchStockMap(allZoneRows)
        val zonesByEventId = allZoneRows.groupBy { it[ZonesTable.eventId] }

        val content =
            eventRows.map { row ->
                val eventInternalId = row[EventsTable.id]
                val zoneRows = zonesByEventId[eventInternalId] ?: emptyList()
                createEventResponse(row, zoneRows, stockMap)
            }

        val totalPages =
            if (size == 0) {
                0
            } else {
                ((totalElements + size - 1) / size).toInt()
            }

        return PageResponse(
            content = content,
            pageable = PageableMeta(page, size, totalElements, totalPages),
        )
    }

    /** Story 11.2: 이벤트 상세 및 실시간 재고 조회 */
    fun getEventDetail(eventPublicId: String): EventDetailResponse {
        val (eventRow, zoneRows) =
            transaction {
                val event =
                    eventRepository.findByPublicId(eventPublicId)
                        ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

                val zones =
                    ZonesTable
                        .selectAll()
                        .where { ZonesTable.eventId eq event[EventsTable.id] }
                        .toList()

                Pair(event, zones)
            }

        val stockMap = fetchStockMap(zoneRows)

        val zoneResponses =
            zoneRows.map { zoneRow ->
                val zoneInternalId = zoneRow[ZonesTable.id]
                val currentStock = stockMap[zoneInternalId] ?: zoneRow[ZonesTable.totalCapacity]

                ZoneStockResponse(
                    zoneId = zoneRow[ZonesTable.publicId],
                    name = zoneRow[ZonesTable.name],
                    unitPrice = zoneRow[ZonesTable.unitPrice],
                    totalCapacity = zoneRow[ZonesTable.totalCapacity],
                    currentStock = currentStock,
                )
            }

        return EventDetailResponse(
            eventId = eventRow[EventsTable.publicId],
            name = eventRow[EventsTable.name],
            description = eventRow[EventsTable.description],
            location = eventRow[EventsTable.location],
            posterUrl = eventRow[EventsTable.posterUrl],
            startTime = eventRow[EventsTable.startTime],
            endTime = eventRow[EventsTable.endTime],
            status = EventStatus.valueOf(eventRow[EventsTable.status]),
            zones = zoneResponses,
        )
    }

    private fun fetchStockMap(zoneRows: List<ResultRow>): Map<Long, Int> {
        val zoneIds = zoneRows.map { it[ZonesTable.id] }
        val redisKeys = zoneIds.map { "ZONE:$it:stock" }
        val redisStocks =
            if (redisKeys.isNotEmpty()) {
                redisTemplate.opsForValue().multiGet(redisKeys)
            } else {
                emptyList()
            }

        return zoneIds
            .zip(redisStocks)
            .mapNotNull { (id, stock) -> stock?.toIntOrNull()?.let { id to it } }
            .toMap()
    }

    private fun createEventResponse(
        row: ResultRow,
        zoneRows: List<ResultRow>,
        stockMap: Map<Long, Int>,
    ): EventResponse {
        val minPrice = zoneRows.minOfOrNull { it[ZonesTable.unitPrice] } ?: 0
        val isSoldOut =
            zoneRows.isNotEmpty() &&
                zoneRows.all { zoneRow ->
                    val zoneInternalId = zoneRow[ZonesTable.id]
                    val currentStock = stockMap[zoneInternalId] ?: zoneRow[ZonesTable.totalCapacity]
                    currentStock <= 0
                }

        return EventResponse(
            eventId = row[EventsTable.publicId],
            name = row[EventsTable.name],
            location = row[EventsTable.location],
            startTime = row[EventsTable.startTime],
            posterUrl = row[EventsTable.posterUrl],
            status = EventStatus.valueOf(row[EventsTable.status]),
            minPrice = minPrice,
            isSoldOut = isSoldOut,
        )
    }

    private fun createEmptyPageResponse(
        page: Int,
        size: Int,
    ): PageResponse<EventResponse> =
        PageResponse(
            content = emptyList(),
            pageable =
                PageableMeta(
                    pageNumber = page,
                    pageSize = size,
                    totalElements = 0,
                    totalPages = 0,
                ),
        )
}
