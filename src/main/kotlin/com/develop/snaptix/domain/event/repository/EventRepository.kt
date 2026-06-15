package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.castToDateTime
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.ZoneId

@Repository
class EventRepository {
    
    // 단일 이벤트 상세 조회용
    fun findByPublicId(publicId: String): ResultRow? =
        EventsTable.select { EventsTable.publicId eq publicId }.singleOrNull()

    // 조건부 필터링 및 페이징 적용된 목록 조회
    fun findEventsWithFilters(
        status: String,
        location: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
        page: Int,
        size: Int,
        sortBy: String,
        sortDir: String
    ): Pair<List<ResultRow>, Long> {
        val query = EventsTable.select { EventsTable.status eq status }

        // 동적 필터링 적용 (Asia/Seoul 타임존 기준 처리)
        location?.let { query.andWhere { EventsTable.location like "%$it%" } }
        startDate?.let {
            val startInstant = it.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            query.andWhere { EventsTable.startTime greaterEq startInstant }
        }
        endDate?.let {
            val endInstant = it.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            query.andWhere { EventsTable.startTime less endInstant }
        }

        // 전체 요소 개수 (페이징 메타데이터용)
        val totalElements = query.count()

        // 동적 정렬 적용
        val sortColumn = when (sortBy) {
            "name" -> EventsTable.name
            "createdAt" -> EventsTable.createdAt
            else -> EventsTable.startTime
        }
        val sortOrder = if (sortDir.lowercase() == "desc") SortOrder.DESC else SortOrder.ASC
        query.orderBy(sortColumn to sortOrder)

        // 페이징 처리
        query.limit(size, offset = (page * size).toLong())

        return query.toList() to totalElements
    }
}