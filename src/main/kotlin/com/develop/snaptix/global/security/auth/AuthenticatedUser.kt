package com.develop.snaptix.global.security.auth

import com.develop.snaptix.domain.user.entity.UserRole

data class AuthenticatedUser(
    val userId: Long,
    val role: UserRole,
)
