package com.develop.snaptix.domain.auth.dto

import com.develop.snaptix.domain.user.entity.UserRole
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "로그인 응답")
data class LoginResponse(
    @field:Schema(description = "로그인한 사용자 고유 ID", example = "10045")
    val userId: Long,
    @field:Schema(description = "사용자 권한", example = "USER")
    val role: UserRole,
)
