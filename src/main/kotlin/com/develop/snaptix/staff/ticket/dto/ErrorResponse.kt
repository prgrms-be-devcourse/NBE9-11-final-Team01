package com.develop.snaptix.staff.ticket.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "공통 에러 응답 형식")
data class ErrorResponse(
    @field:Schema(
        description = "에러 코드 (비즈니스 로직 식별용)",
        example = "VALIDATION_FAILED",
    )
    val code: String,
    @field:Schema(
        description = "사용자에게 보여줄 에러 메시지",
        example = "요청 형식이 올바르지 않습니다.",
    )
    val message: String,
    @field:Schema(description = "필드 에러 목록 (400 예외 시 포함)", nullable = true)
    val errors: List<FieldError>? = null,
)

@Schema(description = "필드 에러 상세 정보")
data class FieldError(
    @field:Schema(description = "에러가 발생한 필드명", example = "ticketCode")
    val field: String,
    @field:Schema(description = "에러 발생 사유", example = "티켓 코드는 필수입니다.")
    val reason: String,
)
