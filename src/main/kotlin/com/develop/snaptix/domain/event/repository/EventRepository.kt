package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant

data class EventRecord(
    val id: Long,
    val publicId: String,
    val name: String,
    val status: String,
)

/**
 * 재구축/드리프트 대상 이벤트 상세. (작업 명세서 6.2 — event:info 재구축 필드)
 */
data class EventDetail(
    val id: Long,
    val publicId: String,
    val name: String,
    val description: String?,
    val location: String,
    val startTime: Instant,
    val endTime: Instant,
    val posterUrl: String?,
    val status: String,
)

data class EventListSearchCondition(
    val page: Int,
    val size: Int,
    val sortBy: EventListSortBy,
    val sortDir: EventListSortDir,
    val location: String?,
    val startTimeFrom: Instant?,
    val startTimeBefore: Instant?,
)

enum class EventListSortBy {
    START_TIME,
    CREATED_AT,
    NAME,
}

enum class EventListSortDir {
    ASC,
    DESC,
}

data class EventListPageRecord(
    val events: List<EventListEventRecord>,
    val zones: List<EventListZoneRecord>,
    val totalElements: Long,
)

data class EventListEventRecord(
    val id: Long,
    val publicId: String,
    val name: String,
    val location: String,
    val startTime: Instant,
    val posterUrl: String?,
    val status: EventStatus,
)

data class EventListZoneRecord(
    val eventId: Long,
    val zoneId: Long,
    val unitPrice: Int,
)

data class EventDetailQueryResult(
    val event: EventDetail,
    val zones: List<EventDetailZoneRecord>,
)

data class EventDetailZoneRecord(
    val publicId: String,
    val name: String,
    val unitPrice: Int,
    val totalCapacity: Int,
)

@Repository
class EventRepository {
    fun findByPublicId(publicId: String): EventRecord? = transaction {
        EventsTable
            .selectAll()
            .where { EventsTable.publicId eq publicId }
            .singleOrNull()
            ?.toRecord()
    }

    fun findById(id: Long): EventRecord? = transaction {
        EventsTable
            .selectAll()
            .where { EventsTable.id eq id }
            .singleOrNull()
            ?.toRecord()
    }

    /** 활성 이벤트(`status != CLOSED`) 상세. 재구축·드리프트 대상. (작업 명세서 §6.2·§6.5) */
    fun findActiveEvents(): List<EventDetail> = transaction {
        EventsTable
            .selectAll()
            .where { EventsTable.status neq EventStatus.CLOSED.name }
            .map { it.toDetail() }
    }

    fun findEventDetailByPublicId(publicId: String): EventDetailQueryResult? = transaction {
        val publicStatuses = PUBLIC_DETAIL_STATUSES.map { it.name }

        EventsTable
            .selectAll()
            .where {
                (EventsTable.publicId eq publicId) and
                    (EventsTable.status inList publicStatuses)
            }.singleOrNull()
            ?.toDetail()
            ?.let { event ->
                EventDetailQueryResult(
                    event = event,
                    zones = findDetailZoneRecordsByEventId(event.id),
                )
            }
    }

    fun insert(
        publicId: String,
        name: String,
        location: String,
        startTime: Instant,
        endTime: Instant,
        status: String,
    ): Long = transaction {
        EventsTable.insert {
            it[EventsTable.publicId] = publicId
            it[EventsTable.name] = name
            it[EventsTable.location] = location
            it[EventsTable.startTime] = startTime
            it[EventsTable.endTime] = endTime
            it[EventsTable.status] = status
        }[EventsTable.id]
    }

    fun insertEvent(
        publicId: String,
        name: String,
        description: String?,
        location: String,
        startTime: Instant,
        endTime: Instant,
        posterUrl: String?,
        status: EventStatus,
    ): EventInsertResult {
        val id =
            EventsTable.insert {
                it[EventsTable.publicId] = publicId
                it[EventsTable.name] = name
                it[EventsTable.description] = description
                it[EventsTable.location] = location
                it[EventsTable.startTime] = startTime
                it[EventsTable.endTime] = endTime
                it[EventsTable.posterUrl] = posterUrl
                it[EventsTable.status] = status.name
            }[EventsTable.id]

        return EventInsertResult(id = id, publicId = publicId)
    }

    fun updateStatusByPublicId(
        publicId: String,
        currentStatus: EventStatus,
        status: EventStatus,
    ): Int = EventsTable.update({
        (EventsTable.publicId eq publicId) and (EventsTable.status eq currentStatus.name)
    }) {
        it[EventsTable.status] = status.name
        it[EventsTable.updatedAt] = Instant.now()
    }

    fun findPublicEventPage(condition: EventListSearchCondition): EventListPageRecord = transaction {
        val where = publicEventWhere(condition)
        val totalElements = EventsTable.selectAll().where(where).count()
        val events =
            EventsTable
                .selectAll()
                .where(where)
                .orderBy(condition.sortBy.toColumn() to condition.sortDir.toSortOrder())
                .limit(condition.size)
                .offset(condition.page.toLong() * condition.size.toLong())
                .map { it.toListEventRecord() }
        val zones =
            events
                .map { it.id }
                .takeIf { it.isNotEmpty() }
                ?.let(::findZoneRecordsByEventIds)
                .orEmpty()

        EventListPageRecord(
            events = events,
            zones = zones,
            totalElements = totalElements,
        )
    }

    private fun publicEventWhere(condition: EventListSearchCondition): Op<Boolean> {
        var where: Op<Boolean> = EventsTable.status eq EventStatus.ON_SALE.name

        condition.location?.takeIf { it.isNotBlank() }?.let { location ->
            where = where and (EventsTable.location like "%$location%")
        }
        condition.startTimeFrom?.let { startTimeFrom ->
            where = where and (EventsTable.startTime greaterEq startTimeFrom)
        }
        condition.startTimeBefore?.let { startTimeBefore ->
            where = where and (EventsTable.startTime less startTimeBefore)
        }

        return where
    }

    private fun findZoneRecordsByEventIds(eventIds: List<Long>): List<EventListZoneRecord> = ZonesTable
        .selectAll()
        .where { ZonesTable.eventId inList eventIds }
        .map {
            EventListZoneRecord(
                eventId = it[ZonesTable.eventId],
                zoneId = it[ZonesTable.id],
                unitPrice = it[ZonesTable.unitPrice],
            )
        }

    private fun findDetailZoneRecordsByEventId(eventId: Long): List<EventDetailZoneRecord> = ZonesTable
        .selectAll()
        .where { ZonesTable.eventId eq eventId }
        .map {
            EventDetailZoneRecord(
                publicId = it[ZonesTable.publicId],
                name = it[ZonesTable.name],
                unitPrice = it[ZonesTable.unitPrice],
                totalCapacity = it[ZonesTable.totalCapacity],
            )
        }

    private companion object {
        val PUBLIC_DETAIL_STATUSES = setOf(EventStatus.ON_SALE, EventStatus.SOLD_OUT)
    }

    private fun EventListSortBy.toColumn() = when (this) {
        EventListSortBy.START_TIME -> EventsTable.startTime
        EventListSortBy.CREATED_AT -> EventsTable.createdAt
        EventListSortBy.NAME -> EventsTable.name
    }

    private fun EventListSortDir.toSortOrder() = when (this) {
        EventListSortDir.ASC -> SortOrder.ASC
        EventListSortDir.DESC -> SortOrder.DESC
    }

    private fun ResultRow.toListEventRecord() = EventListEventRecord(
        id = this[EventsTable.id],
        publicId = this[EventsTable.publicId],
        name = this[EventsTable.name],
        location = this[EventsTable.location],
        startTime = this[EventsTable.startTime],
        posterUrl = this[EventsTable.posterUrl],
        status = EventStatus.valueOf(this[EventsTable.status]),
    )

    private fun ResultRow.toRecord() = EventRecord(
        id = this[EventsTable.id],
        publicId = this[EventsTable.publicId],
        name = this[EventsTable.name],
        status = this[EventsTable.status],
    )

    private fun ResultRow.toDetail() = EventDetail(
        id = this[EventsTable.id],
        publicId = this[EventsTable.publicId],
        name = this[EventsTable.name],
        description = this[EventsTable.description],
        location = this[EventsTable.location],
        startTime = this[EventsTable.startTime],
        endTime = this[EventsTable.endTime],
        posterUrl = this[EventsTable.posterUrl],
        status = this[EventsTable.status],
    )
}
