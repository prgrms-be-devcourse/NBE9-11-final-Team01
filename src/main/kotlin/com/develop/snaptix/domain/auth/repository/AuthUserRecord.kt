package com.develop.snaptix.domain.auth.repository

import com.develop.snaptix.domain.user.entity.UserRole

data class AuthUserRecord(
    val id: Long,
    val email: String,
    val password: String,
    val role: UserRole,
)
