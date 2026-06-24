package com.develop.snaptix.domain.zone.dto

data class ZoneWithEventId(
    val id: Long,
    val eventId: Long,
    val totalCapacity: Int,
)
