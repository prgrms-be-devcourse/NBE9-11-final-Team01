package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

@Schema(description = "이벤트 상태 변경 요청")
data class EventStatusUpdateRequest(
    @field:Schema(description = "변경할 이벤트 상태", example = "ON_SALE")
    @field:NotNull(message = "변경할 이벤트 상태는 필수입니다.")
    val status: EventStatus,
)
