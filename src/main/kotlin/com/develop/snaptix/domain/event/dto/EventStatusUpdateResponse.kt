package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus

data class EventStatusUpdateResponse(
    val eventId: String,
    val status: EventStatus,
    val message: String,
)
