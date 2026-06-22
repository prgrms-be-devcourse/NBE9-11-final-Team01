package com.develop.snaptix.staff.ticket.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "검표 요청 DTO")
data class TicketVerifyRequest(
    @field:NotBlank
    @field:Schema(
        description = "이벤트 Public ID(UUID)",
        example = "550e8400-e29b-41d4-a716-446655440000",
    )
    val eventId: String,
    @field:NotBlank
    @field:Schema(
        description = "티켓 코드(UUID)",
        example = "550e8400-e29b-41d4-a716-446655440111",
    )
    val ticketCode: String,
)
