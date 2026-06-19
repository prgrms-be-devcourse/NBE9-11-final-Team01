package com.develop.snaptix.staff.ticket.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "현장 티켓 검증 응답 DTO")
data class TicketVerifyResponse(
    @field:Schema(description = "티켓 코드", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val ticketCode: String,
    @field:Schema(description = "이벤트 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val eventId: String,
    @field:Schema(description = "티켓 상태 (USED)", example = "USED")
    val status: String,
    @field:Schema(
        description = "입장 처리 시각 (ISO-8601)",
        example = "2026-08-15T18:12:04Z",
        nullable = true,
    )
    val usedAt: String?,
    @field:Schema(description = "처리 결과 메시지", example = "입장 처리되었습니다.")
    val message: String,
)
