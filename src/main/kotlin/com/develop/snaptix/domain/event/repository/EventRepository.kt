package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class EventRepository {
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
}
