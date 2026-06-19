package com.develop.snaptix.staff.ticket.error

import com.develop.snaptix.staff.ticket.dto.ErrorCode

class EventMismatchException : TicketVerifyException(ErrorCode.EVENT_MISMATCH)
