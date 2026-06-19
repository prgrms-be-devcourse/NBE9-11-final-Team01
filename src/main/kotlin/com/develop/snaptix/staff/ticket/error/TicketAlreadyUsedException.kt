package com.develop.snaptix.staff.ticket.error

import com.develop.snaptix.staff.ticket.dto.ErrorCode

class TicketAlreadyUsedException : TicketVerifyException(ErrorCode.TICKET_ALREADY_USED)
