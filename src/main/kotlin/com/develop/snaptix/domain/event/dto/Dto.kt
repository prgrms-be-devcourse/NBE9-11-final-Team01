package com.develop.snaptix.domain.event.dto

import java.time.Instant

enum class EventStatus {
    PENDING,
    ON_SALE,
    SOLD_OUT,
    CLOSED,
}

data class PageResponse<T>(
    val content: List<T>,
    val pageable: PageableMeta,
)

data class PageableMeta(
    val pageNumber: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class EventResponse(
    val eventId: String,
    val name: String,
    val location: String,
    val startTime: Instant,
    val posterUrl: String?,
    val status: EventStatus,
    val minPrice: Int,
    val isSoldOut: Boolean,
)

data class EventDetailResponse(
    val eventId: String,
    val name: String,
    val description: String?,
    val location: String,
    val posterUrl: String?,
    val startTime: Instant,
    val endTime: Instant,
    val status: EventStatus,
    val zones: List<ZoneStockResponse>,
)

data class ZoneStockResponse(
    val zoneId: String,
    val name: String,
    val unitPrice: Int,
    val totalCapacity: Int,
    val currentStock: Int,
)
