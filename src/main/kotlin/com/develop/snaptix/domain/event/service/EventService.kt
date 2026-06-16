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
import global.exception.BusinessException
import global.exception.ErrorCode
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.v1.core.db.transactions.transaction
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    /** Story 11.1: 이벤트 목록 조회 (내부적으로 ON_SALE 조건 고정 및 동적 필터링 적용) */
    fun getEvents(
        page: Int,
        size: Int,
        sortBy: String,
        sortDir: String,
        location: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): PageResponse<EventResponse> =
        transaction {
            val (rows, totalElements) =
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

            val content =
                rows.map { row ->
                    val eventInternalId = row[EventsTable.id]
                    val zoneRows = ZonesTable.select { ZonesTable.eventId eq eventInternalId }.toList()
                    val minPrice = zoneRows.minOfOrNull { it[ZonesTable.unitPrice] } ?: 0

                    var isSoldOut = true
                    if (zoneRows.isNotEmpty()) {
                        isSoldOut =
                            zoneRows.all { zoneRow ->
                                val zoneInternalId = zoneRow[ZonesTable.id]
                                val redisKey = "ZONE:$zoneInternalId:stock"
                                val currentStock =
                                    redisTemplate.opsForValue().get(redisKey)?.toInt()
                                        ?: zoneRow[ZonesTable.totalCapacity]
                                currentStock <= 0
                            }
                    }

                    EventResponse(
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

            val totalPages = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()

            PageResponse(
                content = content,
                pageable =
                    PageableMeta(
                        pageNumber = page,
                        pageSize = size,
                        totalElements = totalElements,
                        totalPages = totalPages,
                    ),
            )
        }

    /** Story 11.2: 이벤트 상세 및 실시간 재고 조회 */
    fun getEventDetail(eventPublicId: String): EventDetailResponse =
        transaction {
            val eventRow =
                eventRepository.findByPublicId(eventPublicId)
                    ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

            val eventInternalId = eventRow[EventsTable.id]

            val zoneResponses =
                ZonesTable
                    .select { ZonesTable.eventId eq eventInternalId }
                    .map { zoneRow ->
                        val zoneInternalId = zoneRow[ZonesTable.id]
                        val zonePublicId = zoneRow[ZonesTable.publicId]

                        val redisKey = "ZONE:$zoneInternalId:stock"
                        val currentStock =
                            redisTemplate.opsForValue().get(redisKey)?.toInt()
                                ?: zoneRow[ZonesTable.totalCapacity]

                        ZoneStockResponse(
                            zoneId = zonePublicId,
                            name = zoneRow[ZonesTable.name],
                            unitPrice = zoneRow[ZonesTable.unitPrice],
                            totalCapacity = zoneRow[ZonesTable.totalCapacity],
                            currentStock = currentStock,
                        )
                    }

            EventDetailResponse(
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
}
