package com.develop.snaptix.global.exception.redis

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode

class RateLimitExceededException(
    val retryAfterSeconds: Long = 1L,
) : BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED)
