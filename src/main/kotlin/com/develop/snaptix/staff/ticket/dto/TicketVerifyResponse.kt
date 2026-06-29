package com.develop.snaptix.staff.ticket.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "검표 응답 DTO")
data class TicketVerifyResponse(
    @field:Schema(description = "티켓 코드")
    val ticketCode: String,
    @field:Schema(description = "이벤트 Public ID")
    val eventId: String,
    @field:Schema(description = "티켓 상태")
    val status: String,
    @field:Schema(
        description = "입장 처리 시각(UTC ISO-8601)",
        example = "2026-08-15T18:12:04Z",
    )
    val usedAt: String,
    @field:Schema(
        description = "결과 메시지",
        example = "입장 처리되었습니다.",
    )
    val message: String,
)
