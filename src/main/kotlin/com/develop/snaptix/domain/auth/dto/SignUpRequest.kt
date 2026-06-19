package com.develop.snaptix.domain.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "회원가입 요청")
data class SignUpRequest(
    @field:Schema(
        description = "사용자 로그인 ID로 사용할 이메일",
        example = "user@example.com",
        maxLength = 255,
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이어야 합니다.")
    @field:Size(max = 255, message = "이메일은 255자를 초과할 수 없습니다.")
    val email: String,
    @field:Schema(
        description = "영문, 숫자, 특수문자를 각각 1개 이상 포함하는 비밀번호",
        example = "SecurePass123!",
        minLength = 8,
        maxLength = 20,
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, max = 20, message = "비밀번호는 8자 이상 20자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.",
    )
    val password: String,
)
