package com.develop.snaptix.domain.auth.controller

import com.develop.snaptix.domain.auth.dto.LoginRequest
import com.develop.snaptix.domain.auth.dto.LoginResponse
import com.develop.snaptix.domain.auth.dto.LogoutResponse
import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.dto.SignUpResponse
import com.develop.snaptix.domain.auth.service.AuthService
import com.develop.snaptix.global.security.jwt.CookieProvider
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val cookieProvider: CookieProvider,
) {
    companion object {
        private const val LOGOUT_SUCCESS_MESSAGE = "로그아웃이 성공적으로 처리되었습니다."
    }

    @PostMapping("/signup")
    fun signUp(
        @Valid @RequestBody request: SignUpRequest,
    ): ResponseEntity<SignUpResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(authService.signUp(request))

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
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
