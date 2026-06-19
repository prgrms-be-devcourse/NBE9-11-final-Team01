package com.develop.snaptix.domain.auth.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "회원가입 응답")
data class SignUpResponse(
    @field:Schema(description = "생성된 사용자 고유 ID", example = "10045")
    val userId: Long,
    @field:Schema(description = "가입된 이메일 주소", example = "user@example.com")
    val email: String,
)
