package com.develop.snaptix.domain.event.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ZoneCreateRequest(
    @field:NotBlank(message = "구역명은 필수입니다.")
    @field:Size(max = 50, message = "구역명은 50자를 초과할 수 없습니다.")
    val name: String,
    @field:Min(value = 100, message = "구역 단가는 100원 이상이어야 합니다.")
    val unitPrice: Int,
    @field:Min(value = 1, message = "구역 수용 인원은 1명 이상이어야 합니다.")
    @field:Max(value = 100000, message = "구역 수용 인원은 100000명을 초과할 수 없습니다.")
    val totalCapacity: Int,
)
