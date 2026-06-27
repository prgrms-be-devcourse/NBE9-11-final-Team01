package com.develop.snaptix.domain.order.scheduler

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

data class OrderStreamTrimTarget(
    val eventId: Long,
    val eventPublicId: String,
)

@Repository
class OrderStreamTrimTargetRepository {
    fun findTargets(): List<OrderStreamTrimTarget> = transaction {
        EventsTable
            .selectAll()
            .where { EventsTable.status neq EventStatus.CLOSED.name }
            .map {
                OrderStreamTrimTarget(
                    eventId = it[EventsTable.id],
                    eventPublicId = it[EventsTable.publicId],
                )
            }
    }
}
