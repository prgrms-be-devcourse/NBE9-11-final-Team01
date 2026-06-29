package com.develop.snaptix.global.exception

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 공통 에러 응답 DTO
 *
 * @property code    에러를 식별하는 고유 코드 (클라이언트 분기 처리용, 예: "TICKET-001")
 * @property message 에러 설명 (사용자 노출용)
 * @property errors  400 Bad Request 시 필드별 유효성 검사 실패 상세 목록 (선택)
 */
@Schema(description = "공통 에러 응답")
data class ErrorResponse(
    @field:Schema(description = "에러 식별 코드", example = "COMMON-009")
    val code: String,
    @field:Schema(description = "에러 메시지", example = "입력값 검증에 실패했습니다.")
    val message: String,
    @field:Schema(description = "필드별 유효성 검사 실패 상세 목록")
    val errors: List<FieldError>? = null,
) {
    /**
     * @property field  에러가 발생한 필드명 (예: "email")
     * @property reason 에러 상세 사유 (예: "이미 사용 중인 이메일입니다.")
     */
    @Schema(description = "필드 에러 상세")
    data class FieldError(
        @field:Schema(description = "에러가 발생한 필드명", example = "email")
        val field: String,
        @field:Schema(description = "에러 상세 사유", example = "올바른 이메일 형식이어야 합니다.")
        val reason: String,
    )
}
