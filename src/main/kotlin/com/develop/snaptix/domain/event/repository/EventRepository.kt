package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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

@Repository
class EventRepository {
    fun findByPublicId(publicId: String): EventRecord? =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.publicId eq publicId }
                .singleOrNull()
                ?.toRecord()
        }

    fun findById(id: Long): EventRecord? =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.id eq id }
                .singleOrNull()
                ?.toRecord()
        }

    /** 활성 이벤트(`status != CLOSED`) 상세. 재구축·드리프트 대상. (작업 명세서 §6.2·§6.5) */
    fun findActiveEvents(): List<EventDetail> =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.status neq EventStatus.CLOSED.name }
                .map { it.toDetail() }
        }

    fun insert(
        publicId: String,
        name: String,
        location: String,
        startTime: Instant,
        endTime: Instant,
        status: String,
    ): Long =
        transaction {
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
    ): Int =
        EventsTable.update({
            (EventsTable.publicId eq publicId) and (EventsTable.status eq currentStatus.name)
        }) {
            it[EventsTable.status] = status.name
            it[EventsTable.updatedAt] = Instant.now()
        }

    private fun ResultRow.toRecord() =
        EventRecord(
            id = this[EventsTable.id],
            publicId = this[EventsTable.publicId],
            name = this[EventsTable.name],
            status = this[EventsTable.status],
        )

    private fun ResultRow.toDetail() =
        EventDetail(
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
