package com.develop.snaptix.domain.auth.controller

import com.develop.snaptix.domain.auth.dto.LoginRequest
import com.develop.snaptix.domain.auth.dto.LoginResponse
import com.develop.snaptix.domain.auth.dto.LogoutResponse
import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.dto.SignUpResponse
import com.develop.snaptix.domain.auth.service.AuthService
import com.develop.snaptix.global.exception.ErrorResponse
import com.develop.snaptix.global.security.jwt.CookieProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "Auth", description = "회원가입, 로그인, 로그아웃 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val cookieProvider: CookieProvider,
) {
    companion object {
        private const val LOGOUT_SUCCESS_MESSAGE = "로그아웃이 성공적으로 처리되었습니다."
    }

    @Operation(
        summary = "회원가입",
        description = "이메일과 비밀번호로 신규 사용자 계정을 생성합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "회원가입 성공",
                content = [Content(schema = Schema(implementation = SignUpResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "이메일 형식 오류, 비밀번호 규칙 미준수 등 입력값 검증 실패",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "이미 등록된 이메일",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping("/signup")
    fun signUp(
        @SwaggerRequestBody(
            description = "회원가입 요청 정보",
            required = true,
            content = [Content(schema = Schema(implementation = SignUpRequest::class))],
        )
        @Valid
        @RequestBody
        request: SignUpRequest,
    ): ResponseEntity<SignUpResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(authService.signUp(request))

    @Operation(
        summary = "로그인 및 JWT 쿠키 발급",
        description = "이메일과 비밀번호를 검증하고 accessToken, refreshToken을 응답 body가 아닌 Set-Cookie 헤더로 발급합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "로그인 성공",
                content = [Content(schema = Schema(implementation = LoginResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "이메일 형식 오류, 비밀번호 누락 등 입력값 검증 실패",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "이메일 또는 비밀번호 불일치",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping("/login")
    fun login(
        @SwaggerRequestBody(
            description = "로그인 요청 정보",
            required = true,
            content = [Content(schema = Schema(implementation = LoginRequest::class))],
        )
        @Valid
        @RequestBody
        request: LoginRequest,
    ): ResponseEntity<LoginResponse> {
        val result = authService.login(request)
        val accessTokenCookie = cookieProvider.createAccessTokenCookie(result.accessToken)
        val refreshTokenCookie = cookieProvider.createRefreshTokenCookie(result.refreshToken)

        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
            .body(result.response)
    }

    @Operation(
        summary = "로그아웃",
        description = "accessToken, refreshToken 쿠키를 만료시켜 로그아웃 처리합니다.",
        security = [SecurityRequirement(name = "accessToken")],
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "로그아웃 성공",
                content = [Content(schema = Schema(implementation = LogoutResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "accessToken 쿠키가 없거나 유효하지 않은 경우",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping("/logout")
    fun logout(): ResponseEntity<LogoutResponse> {
        val accessTokenCookie = cookieProvider.expireAccessTokenCookie()
        val refreshTokenCookie = cookieProvider.expireRefreshTokenCookie()

        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
            .body(LogoutResponse(LOGOUT_SUCCESS_MESSAGE))
    }
}
