package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "이벤트 상태 변경 응답")
data class EventStatusUpdateResponse(
    @field:Schema(description = "이벤트 외부 식별자", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val eventId: String,
    @field:Schema(description = "변경된 이벤트 상태", example = "ON_SALE")
    val status: EventStatus,
    @field:Schema(description = "처리 결과 메시지", example = "이벤트 상태가 변경되었습니다.")
    val message: String,
)
