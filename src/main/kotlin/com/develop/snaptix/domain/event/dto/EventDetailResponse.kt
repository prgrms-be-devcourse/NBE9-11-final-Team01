package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@Schema(description = "이벤트 상세 및 구역별 재고 조회 응답")
data class EventDetailResponse(
    @field:Schema(description = "이벤트 외부 식별자", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val eventId: String,
    @field:Schema(description = "이벤트명", example = "SnapTix Concert 2027")
    val name: String,
    @field:Schema(description = "이벤트 설명", example = "인기 아티스트 콘서트입니다.")
    val description: String?,
    @field:Schema(description = "이벤트 장소", example = "올림픽공원 체조경기장")
    val location: String,
    @field:Schema(description = "포스터 이미지 URL", example = "https://cdn.snaptix.kr/events/test.jpg")
    val posterUrl: String?,
    @field:Schema(description = "이벤트 시작 시각", example = "2027-12-25T19:00:00+09:00")
    val startTime: OffsetDateTime,
    @field:Schema(description = "이벤트 종료 시각", example = "2027-12-25T22:00:00+09:00")
    val endTime: OffsetDateTime,
    @field:Schema(description = "이벤트 상태", example = "ON_SALE")
    val status: EventStatus,
    @field:Schema(description = "구역별 재고 정보")
    val zones: List<ZoneStockInfo>,
)

@Schema(description = "구역별 재고 정보")
data class ZoneStockInfo(
    @field:Schema(description = "구역 외부 식별자", example = "550e8400-e29b-41d4-a716-446655440000")
    val zoneId: String,
    @field:Schema(description = "구역명", example = "A구역")
    val name: String,
    @field:Schema(description = "구역 단가", example = "88000")
    val unitPrice: Int,
    @field:Schema(description = "구역 총 수용 인원", example = "100")
    val totalCapacity: Int,
    @field:Schema(description = "현재 잔여 수량", example = "57")
    val currentStock: Int,
)
