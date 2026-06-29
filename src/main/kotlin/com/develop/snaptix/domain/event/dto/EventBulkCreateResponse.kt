package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "이벤트 및 구역 Bulk 등록 응답")
data class EventBulkCreateResponse(
    @field:Schema(description = "등록된 이벤트 외부 식별자", example = "550e8400-e29b-41d4-a716-446655440000")
    val eventId: String,
    @field:Schema(description = "등록된 이벤트명", example = "2027 SnapTix Concert")
    val eventName: String,
    @field:Schema(description = "등록 시 설정된 이벤트 상태", example = "PENDING")
    val status: EventStatus,
    @field:Schema(description = "등록된 구역 목록")
    val registeredZones: List<ZoneCreateResult>,
    @field:Schema(description = "처리 결과 메시지", example = "이벤트 및 2개 구역 등록이 완료되었습니다.")
    val message: String,
)
