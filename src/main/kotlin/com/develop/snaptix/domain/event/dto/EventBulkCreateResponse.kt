package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus

data class EventBulkCreateResponse(
    val eventId: String,
    val eventName: String,
    val status: EventStatus,
    val registeredZones: List<ZoneCreateResult>,
    val message: String,
)
