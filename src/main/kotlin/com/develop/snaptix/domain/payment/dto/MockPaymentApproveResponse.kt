package com.develop.snaptix.domain.payment.dto

import io.swagger.v3.oas.annotations.media.Schema

data class MockPaymentApproveResponse(
    @field:Schema(description = "결제 요청된 주문 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val orderId: String,
    @field:Schema(description = "처리 안내 메시지", example = "결제 요청이 전송되었습니다. 결과는 SSE로 전달됩니다.")
    val message: String = "결제 요청이 전송되었습니다. 결과는 SSE로 전달됩니다.",
)
