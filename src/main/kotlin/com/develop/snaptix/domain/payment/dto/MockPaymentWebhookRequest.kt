package com.develop.snaptix.domain.payment.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

private const val UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

@Schema(description = "Mock PG 결제 결과 Webhook 요청")
data class MockPaymentWebhookRequest(
    @field:Schema(
        description = "결제 결과를 전달할 주문 ID",
        example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    )
    @field:NotBlank(message = "orderId는 필수입니다.")
    @field:Pattern(regexp = UUID_REGEX, message = "올바른 UUID 형식이 아닙니다.")
    val orderId: String,
    @field:Schema(description = "결제 처리 결과", example = "SUCCESS")
    @field:NotNull(message = "paymentStatus는 필수입니다.")
    val paymentStatus: MockPaymentStatus,
    @field:Schema(description = "결제 실패 사유", example = "CARD_DECLINED", nullable = true)
    @field:Size(max = 100, message = "failReason은 100자 이하여야 합니다.")
    val failReason: String? = null,
)
