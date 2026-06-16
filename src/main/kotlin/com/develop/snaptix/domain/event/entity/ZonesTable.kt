package com.develop.snaptix.domain.event.entity

import org.jetbrains.exposed.sql.Table

object ZonesTable : Table("zones") {
    val id = long("id").autoIncrement()
    val publicId = varchar("public_id", 36).uniqueIndex()
    val eventId = long("event_id").references(EventsTable.id)
    val name = varchar("name", 255)
    val unitPrice = integer("unit_price")
    val totalCapacity = integer("total_capacity")
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)

    override val primaryKey = PrimaryKey(id)
}
