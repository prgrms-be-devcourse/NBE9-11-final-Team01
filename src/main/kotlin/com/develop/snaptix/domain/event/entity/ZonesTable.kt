package com.develop.snaptix.domain.event.entity

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

object ZonesTable : Table("zones") {
    val id = long("id").autoIncrement()
    val publicId = varchar("public_id", 36).uniqueIndex()
    val eventId = long("event_id").references(EventsTable.id)
    val name = varchar("name", 255)
    val unitPrice = integer("unit_price")
    val totalCapacity = integer("total_capacity")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)
}
