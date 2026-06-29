package com.develop.snaptix.staff.ticket.exception

import com.develop.snaptix.global.exception.ErrorResponse
import com.develop.snaptix.staff.ticket.error.TicketVerifyException
import com.develop.snaptix.staff.ticket.logging.VerifyAuditLogger
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class TicketVerifyExceptionHandler(
    private val verifyAuditLogger: VerifyAuditLogger,
) {
    @ExceptionHandler(TicketVerifyException::class)
    fun handleTicketVerifyException(exception: TicketVerifyException): ResponseEntity<ErrorResponse> {
        verifyAuditLogger.warn(
            code = exception.code.name,
        )

        return ResponseEntity
            .status(exception.code.status)
            .body(
                ErrorResponse(
                    code = exception.code.name,
                    message = exception.code.defaultMessage,
                ),
            )
    }
}
