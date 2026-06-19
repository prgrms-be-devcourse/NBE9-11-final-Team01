package com.develop.snaptix.staff.ticket.dto

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val defaultMessage: String,
) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 티켓입니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 이벤트입니다."),
    EVENT_MISMATCH(HttpStatus.CONFLICT, "티켓의 이벤트가 일치하지 않습니다."),
    TICKET_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용된 티켓입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
}
