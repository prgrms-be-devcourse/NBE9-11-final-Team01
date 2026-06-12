package global.exception

/**
 * 공통 에러 응답 DTO
 *
 * @property code    에러를 식별하는 고유 코드 (클라이언트 분기 처리용, 예: "TICKET-001")
 * @property message 에러 설명 (사용자 노출용)
 * @property errors  400 Bad Request 시 필드별 유효성 검사 실패 상세 목록 (선택)
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val errors: List<FieldError>? = null,
) {
    /**
     * @property field  에러가 발생한 필드명 (예: "email")
     * @property reason 에러 상세 사유 (예: "이미 사용 중인 이메일입니다.")
     */
    data class FieldError(
        val field: String,
        val reason: String,
    )
}
