package com.develop.snaptix.domain.auth.service

import com.develop.snaptix.domain.auth.dto.LoginResponse

data class LoginResult(
    val response: LoginResponse,
    val accessToken: String,
    val refreshToken: String,
)
