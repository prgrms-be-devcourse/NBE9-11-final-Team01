package com.develop.snaptix.staff.ticket.error

import com.develop.snaptix.staff.ticket.dto.ErrorCode

sealed class TicketVerifyException(
    val code: ErrorCode,
) : RuntimeException(code.defaultMessage)

class TicketNotFoundException : TicketVerifyException(ErrorCode.TICKET_NOT_FOUND)
