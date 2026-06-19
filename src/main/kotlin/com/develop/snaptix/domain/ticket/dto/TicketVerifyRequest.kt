package com.develop.snaptix.staff.ticket.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "현장 티켓 검증 요청 DTO")
data class TicketVerifyRequest(
    @field:Schema(
        description = "검증 대상 이벤트 ID (public_id)",
        example = "550e8400-e29b-41d4-a716-446655440000",
    )
    @field:NotBlank(message = "이벤트 ID는 필수입니다.")
    val eventId: String,
    @field:Schema(
        description = "검증 대상 티켓 코드 (ticket_code)",
        example = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    )
    @field:NotBlank(message = "티켓 코드는 필수입니다.")
    val ticketCode: String,
)
