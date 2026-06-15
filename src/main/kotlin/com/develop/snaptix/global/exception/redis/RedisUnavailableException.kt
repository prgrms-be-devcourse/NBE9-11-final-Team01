package com.develop.snaptix.global.exception.redis

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode

class RedisUnavailableException : BusinessException(ErrorCode.REDIS_UNAVAILABLE)
