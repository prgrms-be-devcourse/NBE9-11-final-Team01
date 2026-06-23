package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@Schema(description = "이벤트 목록 조회 응답")
data class EventListResponse(
    @field:Schema(description = "이벤트 요약 목록")
    val content: List<EventSummaryDto>,
    @field:Schema(description = "페이징 메타데이터")
    val pageable: PageMetadataDto,
)

@Schema(description = "이벤트 요약 정보")
data class EventSummaryDto(
    @field:Schema(description = "이벤트 외부 식별자", example = "550e8400-e29b-41d4-a716-446655440000")
    val eventId: String,
    @field:Schema(description = "이벤트명", example = "2027 SnapTix Concert")
    val name: String,
    @field:Schema(description = "이벤트 장소", example = "KSPO DOME")
    val location: String,
    @field:Schema(description = "이벤트 시작 시각", example = "2027-12-25T19:00:00+09:00")
    val startTime: OffsetDateTime,
    @field:Schema(description = "포스터 이미지 URL", example = "https://cdn.snaptix.kr/events/test.jpg")
    val posterUrl: String?,
    @field:Schema(description = "이벤트 상태", example = "ON_SALE")
    val status: EventStatus,
    @field:Schema(description = "이벤트 내 구역 최저 가격", example = "69000")
    val minPrice: Int,
    @field:Schema(description = "모든 구역 재고 소진 여부", example = "false")
    val isSoldOut: Boolean,
)

@Schema(description = "페이징 메타데이터")
data class PageMetadataDto(
    @field:Schema(description = "현재 페이지 번호", example = "0")
    val pageNumber: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val pageSize: Int,
    @field:Schema(description = "전체 항목 수", example = "45")
    val totalElements: Long,
    @field:Schema(description = "전체 페이지 수", example = "3")
    val totalPages: Int,
)
