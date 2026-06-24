package com.develop.snaptix.domain.payment.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

private const val UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

data class MockPaymentApproveRequest(
    @field:Schema(description = "결제를 진행할 주문 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    @field:NotBlank(message = "orderId는 필수입니다.")
    @field:Pattern(regexp = UUID_REGEX, message = "orderId는 유효한 UUID 형식이어야 합니다.")
    val orderId: String,
)
