package com.develop.snaptix.domain.zone.repository

import com.develop.snaptix.domain.zone.entity.ZonesTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.springframework.stereotype.Repository

@Repository
class ZoneRepository {
    fun insertZone(
        publicId: String,
        eventId: Long,
        name: String,
        unitPrice: Int,
        totalCapacity: Int,
    ): ZoneInsertResult {
        val id =
            ZonesTable.insert {
                it[ZonesTable.publicId] = publicId
                it[ZonesTable.eventId] = eventId
                it[ZonesTable.name] = name
                it[ZonesTable.unitPrice] = unitPrice
                it[ZonesTable.totalCapacity] = totalCapacity
            }[ZonesTable.id]

        return ZoneInsertResult(
            id = id,
            publicId = publicId,
            name = name,
            unitPrice = unitPrice,
            totalCapacity = totalCapacity,
        )
    }
}
