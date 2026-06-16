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
        val (eventRows: List<ResultRow>, totalElements: Long) =
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

        val eventIds: List<Long> = eventRows.map { row: ResultRow -> row[EventsTable.id] }
        val allZoneRows: List<ResultRow> =
            transaction {
                ZonesTable
                    .selectAll()
                    .where { ZonesTable.eventId inList eventIds }
                    .toList()
            }

        val stockMap: Map<Long, Int> = fetchStockMap(allZoneRows)
        val zonesByEventId: Map<Long, List<ResultRow>> =
            allZoneRows.groupBy { row: ResultRow ->
                row[ZonesTable.eventId]
            }

        val content: List<EventResponse> =
            eventRows.map { row: ResultRow ->
                val eventInternalId: Long = row[EventsTable.id]
                val zoneRows: List<ResultRow> = zonesByEventId[eventInternalId] ?: emptyList()
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
        val (eventRow: ResultRow, zoneRows: List<ResultRow>) =
            transaction {
                val event: ResultRow =
                    eventRepository.findByPublicId(eventPublicId)
                        ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

                val zones: List<ResultRow> =
                    ZonesTable
                        .selectAll()
                        .where { ZonesTable.eventId eq event[EventsTable.id] }
                        .toList()

                Pair(event, zones)
            }

        val stockMap: Map<Long, Int> = fetchStockMap(zoneRows)

        val zoneResponses: List<ZoneStockResponse> =
            zoneRows.map { zoneRow: ResultRow ->
                val zoneInternalId: Long = zoneRow[ZonesTable.id]
                val currentStock: Int = stockMap[zoneInternalId] ?: zoneRow[ZonesTable.totalCapacity]

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
        val zoneIds: List<Long> = zoneRows.map { row: ResultRow -> row[ZonesTable.id] }
        val redisKeys: List<String> = zoneIds.map { id: Long -> "ZONE:$id:stock" }
        val redisStocks: List<String?> =
            if (redisKeys.isNotEmpty()) {
                redisTemplate.opsForValue().multiGet(redisKeys) ?: emptyList()
            } else {
                emptyList()
            }

        return zoneIds
            .zip(redisStocks)
            .mapNotNull { (id: Long, stock: String?) ->
                stock?.toIntOrNull()?.let { intStock: Int -> id to intStock }
            }.toMap()
    }

    private fun createEventResponse(
        row: ResultRow,
        zoneRows: List<ResultRow>,
        stockMap: Map<Long, Int>,
    ): EventResponse {
        val minPrice: Int = zoneRows.minOfOrNull { zoneRow: ResultRow -> zoneRow[ZonesTable.unitPrice] } ?: 0
        val isSoldOut: Boolean =
            zoneRows.isNotEmpty() &&
                zoneRows.all { zoneRow: ResultRow ->
                    val zoneInternalId: Long = zoneRow[ZonesTable.id]
                    val currentStock: Int = stockMap[zoneInternalId] ?: zoneRow[ZonesTable.totalCapacity]
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
