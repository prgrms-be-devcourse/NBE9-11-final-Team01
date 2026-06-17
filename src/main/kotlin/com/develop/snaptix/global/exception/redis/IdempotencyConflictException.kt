package com.develop.snaptix.global.exception.redis

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode

/**
 * 동일 사용자·이벤트에 대한 진행 중인 주문이 이미 존재할 때 발생.
 * → HTTP 409 / ErrorCode.DUPLICATE_ORDER
 */
class IdempotencyConflictException : BusinessException(ErrorCode.DUPLICATE_ORDER)
