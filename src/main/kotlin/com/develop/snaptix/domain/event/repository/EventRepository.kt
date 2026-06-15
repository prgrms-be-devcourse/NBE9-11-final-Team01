package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.ZoneId

@Repository
class EventRepository {
    
    fun findByPublicId(publicId: String): ResultRow? =
        EventsTable.select { EventsTable.publicId eq publicId }.singleOrNull()

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

        location?.let { query.andWhere { EventsTable.location like "%$it%" } }
        startDate?.let {
            val startInstant = it.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            query.andWhere { EventsTable.startTime greaterEq startInstant }
        }
        endDate?.let {
            val endInstant = it.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            query.andWhere { EventsTable.startTime less endInstant }
        }

        val totalElements = query.count()

        val sortColumn = when (sortBy) {
            "name" -> EventsTable.name
            "createdAt" -> EventsTable.createdAt
            else -> EventsTable.startTime
        }
        val sortOrder = if (sortDir.lowercase() == "desc") SortOrder.DESC else SortOrder.ASC
        query.orderBy(sortColumn to sortOrder)

        query.limit(size, offset = (page * size).toLong())

        return query.toList() to totalElements
    }
}