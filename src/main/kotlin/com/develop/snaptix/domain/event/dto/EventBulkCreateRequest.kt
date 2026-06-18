package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.URL
import java.time.LocalDateTime

data class EventBulkCreateRequest(
    @field:NotBlank(message = "이벤트명은 필수입니다.")
    @field:Size(max = 100, message = "이벤트명은 100자를 초과할 수 없습니다.")
    val name: String,
    @field:Size(max = 1000, message = "이벤트 설명은 1000자를 초과할 수 없습니다.")
    val description: String? = null,
    @field:NotBlank(message = "이벤트 장소는 필수입니다.")
    @field:Size(max = 200, message = "이벤트 장소는 200자를 초과할 수 없습니다.")
    val location: String,
    @field:NotNull(message = "이벤트 시작 시각은 필수입니다.")
    @field:Future(message = "이벤트 시작 시각은 현재 시각 이후여야 합니다.")
    val startTime: LocalDateTime,
    @field:NotNull(message = "이벤트 종료 시각은 필수입니다.")
    @field:Future(message = "이벤트 종료 시각은 현재 시각 이후여야 합니다.")
    val endTime: LocalDateTime,
    @field:NotNull(message = "초기 이벤트 상태는 필수입니다.")
    val initialStatus: EventStatus,
    @field:URL(message = "포스터 URL 형식이 올바르지 않습니다.")
    val posterUrl: String? = null,
    @field:Valid
    @field:NotEmpty(message = "구역은 1개 이상 등록해야 합니다.")
    @field:Size(max = 50, message = "구역은 최대 50개까지 등록할 수 있습니다.")
    val zones: List<ZoneCreateRequest>,
)
