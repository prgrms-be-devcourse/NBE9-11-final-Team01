package com.develop.snaptix.domain.auth.dto

import com.develop.snaptix.domain.user.entity.UserRole

data class LoginResponse(
    val userId: Long,
    val role: UserRole,
)
