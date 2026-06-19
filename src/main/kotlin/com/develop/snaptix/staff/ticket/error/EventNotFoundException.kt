package com.develop.snaptix.staff.ticket.error

import com.develop.snaptix.staff.ticket.dto.ErrorCode

class EventNotFoundException : TicketVerifyException(ErrorCode.EVENT_NOT_FOUND)
