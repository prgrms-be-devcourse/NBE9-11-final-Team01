package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDate

class EventRepository {
    fun findByPublicId(eventPublicId: String): ResultRow? =
        EventsTable
            .selectAll()
            .where { EventsTable.publicId eq eventPublicId }
            .singleOrNull()

    @Suppress("LongParameterList")
    fun findEventsWithFilters(
        status: String,
        location: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
        page: Int,
        size: Int,
        sortBy: String,
        sortDir: String,
    ): Pair<List<ResultRow>, Long> {
        var query =
            EventsTable
                .selectAll()
                .where { EventsTable.status eq status }

        location?.let {
            query = query.andWhere { EventsTable.location like "%$it%" }
        }
        startDate?.let {
            query = query.andWhere { EventsTable.startTime greaterEq it.atStartOfDay() }
        }
        endDate?.let {
            query = query.andWhere { EventsTable.endTime less it.atStartOfDay() }
        }

        val totalElements = query.count()

        val orderDir =
            if (sortDir.lowercase() == "desc") {
                SortOrder.DESC
            } else {
                SortOrder.ASC
            }

        val orderColumn =
            when (sortBy) {
                "createdAt" -> EventsTable.createdAt
                "name" -> EventsTable.name
                else -> EventsTable.startTime
            }

        val eventRows =
            query
                .orderBy(orderColumn to orderDir)
                .limit(size, offset = (page * size).toLong())
                .toList()

        return Pair(eventRows, totalElements)
    }
}
