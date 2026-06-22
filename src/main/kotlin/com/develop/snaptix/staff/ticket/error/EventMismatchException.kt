package com.develop.snaptix.staff.ticket.error

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode

class EventMismatchException :
    BusinessException(
        ErrorCode.EVENT_MISMATCH,
    )
