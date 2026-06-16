package com.develop.snaptix.global.security.auth

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class CurrentUserProvider {
    fun getCurrentUserId(): Long = getCurrentUser().userId

    fun getCurrentUser(): AuthenticatedUser =
        SecurityContextHolder
            .getContext()
            .authentication
            ?.principal
            ?.let { it as? AuthenticatedUser }
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
}
