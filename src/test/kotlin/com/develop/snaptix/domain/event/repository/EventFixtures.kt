package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * EventRepository 테스트 전용 픽스처.
 *
 * ReconcileFixtures 는 Story-13 전용이므로 event 도메인 테스트는 본 픽스처를 사용한다.
 */
object EventFixtures {
    fun insertEvent(status: EventStatus = EventStatus.ON_SALE): EventFixtureResult = transaction {
        val publicId = UUID.randomUUID().toString()
        val id =
            EventsTable.insert {
                it[EventsTable.publicId] = publicId
                it[EventsTable.name] = "테스트 이벤트 $publicId"
                it[EventsTable.location] = "서울"
                it[EventsTable.startTime] = Instant.now().plus(1, ChronoUnit.DAYS)
                it[EventsTable.endTime] = Instant.now().plus(2, ChronoUnit.DAYS)
                it[EventsTable.status] = status.name
            }[EventsTable.id]

        EventFixtureResult(eventId = id, publicId = publicId)
    }
}

data class EventFixtureResult(
    val eventId: Long,
    val publicId: String,
)
