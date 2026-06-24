package com.develop.snaptix.global.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String,
) {
    // ==================== 공통 (COMMON) ====================
    INTERNAL_SERVER_ERROR(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "COMMON-001",
        "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
    ),
    INVALID_REQUEST_PARAMETER(
        HttpStatus.BAD_REQUEST,
        "COMMON-002",
        "잘못된 요청 파라미터입니다.",
    ),
    NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "COMMON-003",
        "요청한 리소스를 찾을 수 없습니다.",
    ),
    METHOD_NOT_ALLOWED(
        HttpStatus.METHOD_NOT_ALLOWED,
        "COMMON-004",
        "지원하지 않는 HTTP 메서드입니다.",
    ),
    UNSUPPORTED_MEDIA_TYPE(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "COMMON-005",
        "지원하지 않는 Content-Type입니다.",
    ),
    TYPE_MISMATCH(
        HttpStatus.BAD_REQUEST,
        "COMMON-006",
        "요청 파라미터의 타입이 올바르지 않습니다.",
    ),
    PARAM_MISSING(
        HttpStatus.BAD_REQUEST,
        "COMMON-007",
        "필수 파라미터가 누락되었습니다.",
    ),
    DUPLICATE_RESOURCE(
        HttpStatus.CONFLICT,
        "COMMON-008",
        "이미 존재하는 리소스입니다.",
    ),
    VALIDATION_FAILED(
        HttpStatus.BAD_REQUEST,
        "COMMON-009",
        "입력값 검증에 실패했습니다.",
    ),
    TOO_MANY_REQUESTS(
        HttpStatus.TOO_MANY_REQUESTS,
        "COMMON-010",
        "요청 횟수가 초과되었습니다. 잠시 후 다시 시도해주세요.",
    ),
    SERVICE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMMON-011",
        "서비스를 일시적으로 사용할 수 없습니다.",
    ),

    // ==================== 인증 (AUTH) ====================
    // 토큰 없음 — SecurityContext 미설정 → EntryPoint 경유
    TOKEN_MISSING(
        HttpStatus.UNAUTHORIZED,
        "AUTH-001",
        "인증 토큰이 없습니다.",
    ),

    // 토큰 위조/형식 오류
    TOKEN_INVALID(
        HttpStatus.UNAUTHORIZED,
        "AUTH-002",
        "유효하지 않거나 만료된 토큰입니다.",
    ),

    // 토큰 만료 — ExpiredJwtException → 필터에서 직접 응답
    TOKEN_EXPIRED(
        HttpStatus.UNAUTHORIZED,
        "AUTH-003",
        "인증 토큰이 만료되었습니다.",
    ),

    // 로그인 실패 — 열거 공격 방지를 위해 동일 메시지
    INVALID_LOGIN_CREDENTIALS(
        HttpStatus.UNAUTHORIZED,
        "AUTH-004",
        "이메일 또는 비밀번호가 올바르지 않습니다.",
    ),

    // 인가 실패 — AccessDeniedHandler 경유
    ACCESS_DENIED(
        HttpStatus.FORBIDDEN,
        "AUTH-005",
        "접근 권한이 없습니다.",
    ),

    // SecurityContext 비어있을 때
    UNAUTHORIZED(
        HttpStatus.UNAUTHORIZED,
        "AUTH-006",
        "인증이 필요합니다.",
    ),

    // IDOR 방어 — 리소스 소유자 불일치
    FORBIDDEN_ACCESS(
        HttpStatus.FORBIDDEN,
        "AUTH-007",
        "해당 리소스에 접근할 권한이 없습니다.",
    ),

    // ==================== 회원 (MEMBER) ====================
    MEMBER_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "MEMBER-001",
        "존재하지 않는 회원입니다.",
    ),
    DUPLICATE_EMAIL(
        HttpStatus.CONFLICT,
        "MEMBER-002",
        "이미 등록된 이메일입니다.",
    ),

    // ==================== 이벤트 (EVENT) ====================
    EVENT_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "EVENT-001",
        "존재하지 않는 이벤트입니다.",
    ),

    // DB 업데이트 또는 Redis 처리 중 예외
    RECONCILE_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EVENT-002",
        "이벤트 처리 중 오류가 발생했습니다.",
    ),
    EVENT_CREATION_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EVENT-003",
        "이벤트 생성 중 오류가 발생했습니다.",
    ),
    EVENT_REDIS_INITIALIZATION_FAILED(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EVENT-004",
        "Redis 초기화에 실패하여 이벤트를 생성할 수 없습니다.",
    ),
    EVENT_STATUS_CONFLICT(
        HttpStatus.CONFLICT,
        "EVENT-005",
        "이벤트 상태가 변경되어 요청을 처리할 수 없습니다.",
    ),

    // ==================== 결제 (PAYMENT) ====================
    ORDER_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "PAYMENT-001",
        "해당 주문을 찾을 수 없습니다.",
    ),
    ORDER_ACCESS_DENIED(
        HttpStatus.FORBIDDEN,
        "PAYMENT-002",
        "해당 주문에 접근할 권한이 없습니다.",
    ),
    ORDER_NOT_PAYABLE(
        HttpStatus.CONFLICT,
        "PAYMENT-003",
        "결제 가능한 주문 상태가 아닙니다.",
    ),
    ORDER_HOLD_EXPIRED(
        HttpStatus.CONFLICT,
        "PAYMENT-004",
        "결제 대기 시간이 초과되었습니다. 다시 주문을 시도해주세요.",
    ),
    PAYMENT_REQUEST_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "PAYMENT-005",
        "결제 요청 중 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
    ),

    // ==================== 티켓 (TICKET) ====================
    // Path Variable이 UUID 형식(36자리)이 아닌 경우
    INVALID_TICKET_CODE(
        HttpStatus.BAD_REQUEST,
        "TICKET-001",
        "유효하지 않은 티켓 코드 형식입니다.",
    ),
    TICKET_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "TICKET-002",
        "존재하지 않는 티켓입니다.",
    ),
	
    // ==================== 티켓 (TICKET) 추가 6/22 ============
    EVENT_MISMATCH(
        HttpStatus.CONFLICT,
        "TICKET-003",
        "티켓의 이벤트가 일치하지 않습니다.",
    ),

    TICKET_ALREADY_USED(
        HttpStatus.CONFLICT,
        "TICKET-004",
        "이미 사용된 티켓입니다.",
    ),
	
    // ==================== 예약 (RESERVATION) ====================
    RESERVATION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "RESERVATION-001",
        "존재하지 않는 예약입니다.",
    ),

    // ==================== 메트릭 (METRIC) ====================
    METRIC_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "METRIC-001",
        "등록되지 않은 메트릭입니다.",
    ),

    // ==================== Redis ====================
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RL_001", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    DUPLICATE_ORDER(HttpStatus.CONFLICT, "OR_001", "이미 동일한 이벤트에 대한 주문이 진행 중입니다."),
    REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SYS_001", "현재 시스템 점검 중입니다. 잠시 후 다시 시도해주세요."),
    ;

    /** 커스텀 메시지 없이 ErrorResponse 생성 */
    fun toErrorResponse(): ErrorResponse = ErrorResponse(code = code, message = message)

    /** 커스텀 메시지로 ErrorResponse 생성 */
    fun toErrorResponse(customMessage: String): ErrorResponse = ErrorResponse(code = code, message = customMessage)

    /** 필드 에러 목록과 함께 ErrorResponse 생성 (VALIDATION_FAILED 전용) */
    fun toErrorResponse(errors: List<ErrorResponse.FieldError>): ErrorResponse =
        ErrorResponse(code = code, message = message, errors = errors)
}
