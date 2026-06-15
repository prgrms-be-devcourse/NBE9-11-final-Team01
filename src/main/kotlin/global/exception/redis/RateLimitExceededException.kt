package global.exception.redis

import global.exception.BusinessException
import global.exception.ErrorCode

class RateLimitExceededException(
    val retryAfterSeconds: Long = 1L,
) : BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED)
