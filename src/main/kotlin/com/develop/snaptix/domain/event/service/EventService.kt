package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.*
import com.develop.snaptix.domain.event.entity.*
import com.develop.snaptix.domain.event.repository.EventRepository
import global.exception.*
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.v1.core.db.transactions.transaction
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val redisTemplate: StringRedisTemplate
) {
    /** Story 11.1: 이벤트 목록 조회 (내부적으로 ON_SALE 조건 고정 및 동적 필터링 적용) */
    fun getEvents(
        page: Int,
        size: Int,
        sortBy: String,
        sortDir: String,
        location: String?,
        startDate: LocalDate?,
        endDate: LocalDate?
    ): PageResponse<EventResponse> = transaction {
        
        // 1. Repository에 캡슐화된 쿼리 호출
        val (rows, totalElements) = eventRepository.findEventsWithFilters(
            status = EventStatus.ON_SALE.name,
            location = location,
            startDate = startDate,
            endDate = endDate,
            page = page,
            size = size,
            sortBy = sortBy,
            sortDir = sortDir
        )

        // 2. DTO 변환 및 추가 메타데이터 계산
        val content = rows.map { row ->
            val eventInternalId = row[EventsTable.id]
            
            // 각 이벤트의 구역(Zone) 정보를 조회하여 minPrice 및 isSoldOut 계산
            val zoneRows = ZonesTable.select { ZonesTable.eventId eq eventInternalId }.toList()
            
            val minPrice = zoneRows.minOfOrNull { it[ZonesTable.unitPrice] } ?: 0
            
            // 재고 확인 (Redis 또는 DB Fallback을 통한 매진 여부 판별)
            var isSoldOut = true
            if (zoneRows.isNotEmpty()) {
                isSoldOut = zoneRows.all { zoneRow ->
                    val zoneInternalId = zoneRow[ZonesTable.id]
                    val redisKey = "ZONE:$zoneInternalId:stock"
                    val currentStock = redisTemplate.opsForValue().get(redisKey)?.toInt() 
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
                isSoldOut = isSoldOut
            )
        }

        // 3. 페이징 메타데이터 조립
        val totalPages = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()

        PageResponse(
            content = content,
            pageable = PageableMeta(
                pageNumber = page,
                pageSize = size,
                totalElements = totalElements,
                totalPages = totalPages
            )
        )
    }

    /** Story 11.2: 이벤트 상세 및 실시간 재고 조회 */
    fun getEventDetail(eventPublicId: String): EventDetailResponse = transaction {
        // 1. Cache-Aside (단순화: DB 조회를 기점으로 잡고 필요시 고도화)
        val eventRow = eventRepository.findByPublicId(eventPublicId) 
            ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

        val eventInternalId = eventRow[EventsTable.id]

        val zoneResponses = ZonesTable.select { ZonesTable.eventId eq eventInternalId }
            .map { zoneRow ->
                val zoneInternalId = zoneRow[ZonesTable.id]
                val zonePublicId = zoneRow[ZonesTable.publicId]
                
                // Redis에서 'ZONE:{zoneId}:stock' 조회 (zoneId는 내부 PK 사용 규약)
                val redisKey = "ZONE:$zoneInternalId:stock"
                val currentStock = redisTemplate.opsForValue().get(redisKey)?.toInt() 
                    ?: zoneRow[ZonesTable.totalCapacity] // 레디스 미구축/부재 시 total_capacity로 폴백

                ZoneStockResponse(
                    zoneId = zonePublicId,
                    name = zoneRow[ZonesTable.name],
                    unitPrice = zoneRow[ZonesTable.unitPrice],
                    totalCapacity = zoneRow[ZonesTable.totalCapacity],
                    currentStock = currentStock
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
            zones = zoneResponses
        )
    }
}