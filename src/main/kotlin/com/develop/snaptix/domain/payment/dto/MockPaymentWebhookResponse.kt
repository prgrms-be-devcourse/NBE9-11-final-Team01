package com.develop.snaptix.domain.payment.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mock PG 결제 결과 Webhook 응답")
data class MockPaymentWebhookResponse(
    @field:Schema(
        description = "처리된 주문 ID",
        example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    )
    val orderId: String,
    @field:Schema(description = "true: 실제 처리됨, false: 이미 처리되어 스킵", example = "true")
    val processed: Boolean,
    @field:Schema(description = "처리 결과 메시지", example = "결제 결과가 처리되었습니다.")
    val message: String,
)
