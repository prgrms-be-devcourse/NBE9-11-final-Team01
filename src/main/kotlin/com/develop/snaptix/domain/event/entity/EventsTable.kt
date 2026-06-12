package com.develop.snaptix.domain.event.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

object EventsTable : Table("events") {
    val id = long("id").autoIncrement()
    val publicId = varchar("public_id", 36).uniqueIndex()
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val location = varchar("location", 255)
    val startTime = timestamp("start_time")
    val endTime = timestamp("end_time")
    val posterUrl = varchar("poster_url", 500).nullable()

    // PENDING, ON_SALE, SOLD_OUT, CLOSED
    val status = varchar("status", 20)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)
}
