package global.exception

import org.springframework.http.HttpStatus

/**
 * 비즈니스 로직 예외 기본 클래스
 *
 * 사용 예:
 *   throw BusinessException(ErrorCode.TICKET_NOT_FOUND)
 *   throw BusinessException(ErrorCode.VALIDATION_FAILED, "티켓 코드는 UUID 형식이어야 합니다.")
 */
open class BusinessException(
    internal val errorCode: ErrorCode,
    customMessage: String? = null,
) : RuntimeException(customMessage) {
    constructor(errorCode: ErrorCode) : this(errorCode, null)

    val httpStatus: HttpStatus
        get() = errorCode.status

    override val message: String
        get() = super.message ?: errorCode.message

    fun toErrorResponse(): ErrorResponse = errorCode.toErrorResponse(message)
}
