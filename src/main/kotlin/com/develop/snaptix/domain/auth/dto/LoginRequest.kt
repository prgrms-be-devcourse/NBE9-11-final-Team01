package com.develop.snaptix.domain.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "로그인 요청")
data class LoginRequest(
    @field:Schema(
        description = "사용자 로그인 이메일",
        example = "user@example.com",
        maxLength = 255,
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이어야 합니다.")
    @field:Size(max = 255, message = "이메일은 255자를 초과할 수 없습니다.")
    val email: String,
    @field:Schema(
        description = "사용자 비밀번호",
        example = "SecurePass123!",
        format = "password",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    val password: String,
)
