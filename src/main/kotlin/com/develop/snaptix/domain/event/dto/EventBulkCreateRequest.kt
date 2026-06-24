package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.domain.event.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.URL
import java.time.OffsetDateTime

@Schema(description = "이벤트 및 구역 Bulk 등록 요청")
data class EventBulkCreateRequest(
    @field:Schema(description = "이벤트명", example = "2027 SnapTix Concert")
    @field:NotBlank(message = "이벤트명은 필수입니다.")
    @field:Size(max = 100, message = "이벤트명은 100자를 초과할 수 없습니다.")
    val name: String,
    @field:Schema(description = "이벤트 설명", example = "인기 아티스트 콘서트입니다.")
    @field:Size(max = 1000, message = "이벤트 설명은 1000자를 초과할 수 없습니다.")
    val description: String? = null,
    @field:Schema(description = "이벤트 장소", example = "올림픽공원 체조경기장")
    @field:NotBlank(message = "이벤트 장소는 필수입니다.")
    @field:Size(max = 200, message = "이벤트 장소는 200자를 초과할 수 없습니다.")
    val location: String,
    @field:Schema(description = "이벤트 시작 시각. 오프셋 포함 ISO-8601 형식", example = "2027-12-25T19:00:00+09:00")
    @field:NotNull(message = "이벤트 시작 시각은 필수입니다.")
    @field:Future(message = "이벤트 시작 시각은 현재 시각 이후여야 합니다.")
    val startTime: OffsetDateTime,
    @field:Schema(description = "이벤트 종료 시각. startTime 이후여야 합니다.", example = "2027-12-25T22:00:00+09:00")
    @field:NotNull(message = "이벤트 종료 시각은 필수입니다.")
    @field:Future(message = "이벤트 종료 시각은 현재 시각 이후여야 합니다.")
    val endTime: OffsetDateTime,
    @field:Schema(description = "초기 이벤트 상태. 생성 시 PENDING 또는 ON_SALE만 허용", example = "PENDING")
    @field:NotNull(message = "초기 이벤트 상태는 필수입니다.")
    val initialStatus: EventStatus,
    @field:Schema(description = "포스터 이미지 URL", example = "https://cdn.snaptix.kr/events/concert.jpg")
    @field:URL(message = "포스터 URL 형식이 올바르지 않습니다.")
    val posterUrl: String? = null,
    @field:Schema(description = "등록할 구역 목록. 1개 이상 50개 이하")
    @field:Valid
    @field:NotEmpty(message = "구역은 1개 이상 등록해야 합니다.")
    @field:Size(max = 50, message = "구역은 최대 50개까지 등록할 수 있습니다.")
    val zones: List<ZoneCreateRequest>,
)
