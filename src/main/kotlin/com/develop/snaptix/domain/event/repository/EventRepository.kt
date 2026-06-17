package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate
import java.time.ZoneOffset

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
            // LocalDateTime을 DB 컬럼 타입인 Instant로 변환
            query =
                query.andWhere {
                    EventsTable.startTime greaterEq it.atStartOfDay().toInstant(ZoneOffset.UTC)
                }
        }
        endDate?.let {
            // LocalDateTime을 DB 컬럼 타입인 Instant로 변환
            query =
                query.andWhere {
                    EventsTable.endTime less it.atStartOfDay().toInstant(ZoneOffset.UTC)
                }
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
                .limit(size, (page * size).toLong()) // 네임드 파라미터(offset=) 제거
                .toList()

        return Pair(eventRows, totalElements)
    }
}
