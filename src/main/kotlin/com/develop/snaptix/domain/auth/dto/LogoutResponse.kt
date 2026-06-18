package com.develop.snaptix.domain.auth.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "로그아웃 응답")
data class LogoutResponse(
    @field:Schema(description = "로그아웃 처리 결과 메시지", example = "로그아웃이 성공적으로 처리되었습니다.")
    val message: String,
)
