package com.develop.snaptix.domain.order.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

private const val UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

data class OrderRequest(
    @field:NotBlank(message = "eventId는 필수입니다.")
    @field:Pattern(regexp = UUID_REGEX, message = "eventId는 유효한 UUID 형식이어야 합니다.")
    val eventId: String? = null,
    @field:NotNull(message = "zoneId는 필수입니다.")
    val zoneId: Long,
)
