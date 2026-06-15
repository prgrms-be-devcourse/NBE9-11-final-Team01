package com.develop.snaptix.domain.event.dto

import java.time.Instant

enum class EventStatus {
    PENDING, ON_SALE, SOLD_OUT, CLOSED
}

// API 명세에 따른 페이징 공통 응답 포맷
data class PageResponse<T>(
    val content: List<T>,
    val pageable: PageableMeta
)

data class PageableMeta(
    val pageNumber: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int
)

/** 이벤트 목록 조회 응답 (페이로드를 최소화) */
data class EventResponse(
    val eventId: String,
    val name: String,
    val location: String,
    val startTime: Instant,
    val posterUrl: String?,
    val status: EventStatus,
    val minPrice: Int,       // 추가됨: 구역 중 최저가
    val isSoldOut: Boolean   // 추가됨: 전 구역 매진 여부
)

/** 이벤트 상세 및 실시간 재고 조회 응답 */
data class EventDetailResponse(
    val eventId: String,
    val name: String,
    val description: String?,
    val location: String,
    val posterUrl: String?,
    val startTime: Instant,
    val endTime: Instant,
    val status: EventStatus,
    val zones: List<ZoneStockResponse>
)

data class ZoneStockResponse(
    val zoneId: String,
    val name: String,
    val unitPrice: Int,
    val totalCapacity: Int,
    val currentStock: Int
)