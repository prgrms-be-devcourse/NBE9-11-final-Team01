package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.Instant

// ⚠️ EventsTable 정의 파일을 받지 못해 컬럼명은 ERD v3.1 기준으로 참조한다.
//    (publicId/name/location/startTime/endTime/status/description/posterUrl)
//    기존 엔티티의 실제 컬럼명과 다르면 아래 참조만 맞춰주세요.
data class EventRecord(
    val id: Long,
    val publicId: String,
    val name: String,
    val status: String,
)

@Repository
class EventRepository {
    /** 검표 요청의 eventId(public_id, UUID)로 이벤트를 조회한다. */
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

    /** 테스트 픽스처용 삽입. 생성된 id를 반환한다. */
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
            } get EventsTable.id
        }

    private fun ResultRow.toRecord() =
        EventRecord(
            id = this[EventsTable.id],
            publicId = this[EventsTable.publicId],
            name = this[EventsTable.name],
            status = this[EventsTable.status],
        )
}
