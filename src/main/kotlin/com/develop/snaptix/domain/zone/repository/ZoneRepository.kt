package com.develop.snaptix.domain.zone.repository

import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
class ZoneRepository {
    fun insertZones(
        eventId: Long,
        zones: List<ZoneCreateCommand>,
    ): List<ZoneInsertResult> {
        ZonesTable.batchInsert(zones) { zone ->
            this[ZonesTable.publicId] = zone.publicId
            this[ZonesTable.eventId] = eventId
            this[ZonesTable.name] = zone.name
            this[ZonesTable.unitPrice] = zone.unitPrice
            this[ZonesTable.totalCapacity] = zone.totalCapacity
        }

        val rowsByPublicId =
            ZonesTable
                .selectAll()
                .where { ZonesTable.publicId inList zones.map { it.publicId } }
                .associateBy { it[ZonesTable.publicId] }

        return zones.map { zone ->
            rowsByPublicId.getValue(zone.publicId).toInsertResult()
        }
    }

    fun findIdsByEventId(eventId: Long): List<Long> = ZonesTable
        .selectAll()
        .where { ZonesTable.eventId eq eventId }
        .map { it[ZonesTable.id] }

    private fun ResultRow.toInsertResult(): ZoneInsertResult = ZoneInsertResult(
        id = this[ZonesTable.id],
        publicId = this[ZonesTable.publicId],
        name = this[ZonesTable.name],
        unitPrice = this[ZonesTable.unitPrice],
        totalCapacity = this[ZonesTable.totalCapacity],
    )
}
