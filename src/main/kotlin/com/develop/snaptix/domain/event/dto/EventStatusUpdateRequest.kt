package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import jakarta.validation.constraints.NotNull

data class EventStatusUpdateRequest(
    @field:NotNull(message = "변경할 이벤트 상태는 필수입니다.")
    val status: EventStatus,
)
