package com.develop.snaptix.domain.event.entity

import org.jetbrains.exposed.sql.Table

object EventsTable : Table("events") {
    val id = long("id").autoIncrement()
    val publicId = varchar("public_id", 36).uniqueIndex()
    val name = varchar("name", 255)
    val description = text("description")
    val location = varchar("location", 255)
    val startTime = varchar("start_time", 50)
    val endTime = varchar("end_time", 50)
    val status = varchar("status", 50)
    val posterUrl = varchar("poster_url", 500).nullable()
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)

    override val primaryKey = PrimaryKey(id)
}
