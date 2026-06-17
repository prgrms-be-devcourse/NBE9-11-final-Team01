package com.develop.snaptix.domain.event.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "이벤트 진행 상태")
enum class EventStatus {
    PENDING,
    ON_SALE,
    SOLD_OUT,
    CLOSED,
}

@Schema(description = "페이징 처리된 공통 응답 포맷")
data class PageResponse<T>(
    @field:Schema(description = "실제 데이터 목록")
    val content: List<T>,
    @field:Schema(description = "페이징 메타 정보")
    val pageable: PageableMeta,
)

@Schema(description = "페이징 메타 데이터")
data class PageableMeta(
    @field:Schema(description = "현재 페이지 번호 (0부터 시작)", example = "0")
    val pageNumber: Int,
    @field:Schema(description = "페이지당 데이터 개수", example = "20")
    val pageSize: Int,
    @field:Schema(description = "전체 데이터 개수", example = "150")
    val totalElements: Long,
    @field:Schema(description = "전체 페이지 수", example = "8")
    val totalPages: Int,
)

@Suppress("LongParameterList")
@Schema(description = "이벤트 목록 조회 응답 DTO")
data class EventResponse(
    @field:Schema(description = "이벤트 공개 식별자 (UUID)", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val eventId: String,
    @field:Schema(description = "이벤트 명", example = "SnapTix Concert 2026")
    val name: String,
    @field:Schema(description = "이벤트 장소", example = "올림픽공원 체조경기장")
    val location: String,
    @field:Schema(description = "이벤트 시작 시간", example = "2026-07-15T19:00:00Z")
    val startTime: Instant,
    @field:Schema(description = "포스터 이미지 URL", example = "https://example.com/poster.jpg", nullable = true)
    val posterUrl: String?,
    @field:Schema(description = "이벤트 상태", example = "ON_SALE")
    val status: EventStatus,
    @field:Schema(description = "최저가 (구역 중 가장 저렴한 가격)", example = "99000")
    val minPrice: Int,
    @field:Schema(description = "전체 매진 여부 (모든 구역 재고 0)", example = "false")
    val isSoldOut: Boolean,
)

@Suppress("LongParameterList")
@Schema(description = "이벤트 상세 및 실시간 재고 조회 응답 DTO")
data class EventDetailResponse(
    @field:Schema(description = "이벤트 공개 식별자 (UUID)", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val eventId: String,
    @field:Schema(description = "이벤트 명", example = "SnapTix Concert 2026")
    val name: String,
    @field:Schema(description = "이벤트 상세 설명", example = "인기 아티스트 콘서트입니다.", nullable = true)
    val description: String?,
    @field:Schema(description = "이벤트 장소", example = "올림픽공원 체조경기장")
    val location: String,
    @field:Schema(description = "포스터 이미지 URL", example = "https://example.com/poster.jpg", nullable = true)
    val posterUrl: String?,
    @field:Schema(description = "이벤트 시작 시간", example = "2026-07-15T19:00:00Z")
    val startTime: Instant,
    @field:Schema(description = "이벤트 종료 시간", example = "2026-07-15T21:00:00Z")
    val endTime: Instant,
    @field:Schema(description = "이벤트 상태", example = "ON_SALE")
    val status: EventStatus,
    @field:Schema(description = "구역(Zone)별 실시간 재고 목록")
    val zones: List<ZoneStockResponse>,
)

@Schema(description = "구역별 실시간 재고 정보 DTO")
data class ZoneStockResponse(
    @field:Schema(description = "구역 공개 식별자 (UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
    val zoneId: String,
    @field:Schema(description = "구역명", example = "VIP")
    val name: String,
    @field:Schema(description = "티켓 단가", example = "150000")
    val unitPrice: Int,
    @field:Schema(description = "총 수용 인원 (발행 티켓 수)", example = "500")
    val totalCapacity: Int,
    @field:Schema(description = "실시간 잔여 재고", example = "250")
    val currentStock: Int,
)
