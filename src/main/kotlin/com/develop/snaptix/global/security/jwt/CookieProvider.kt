package com.develop.snaptix.global.security.jwt

import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

private const val ROOT_PATH = "/"
private const val REFRESH_PATH = "/api/v1/auth/refresh"
private const val ACCESS_TOKEN_COOKIE = "accessToken"
private const val REFRESH_TOKEN_COOKIE = "refreshToken"
private const val SAME_SITE_STRICT = "Strict"

@Component
class CookieProvider(
    private val jwtProperties: JwtProperties,
) {
    fun createAccessTokenCookie(token: String): ResponseCookie =
        createCookie(
            name = ACCESS_TOKEN_COOKIE,
            value = token,
            path = ROOT_PATH,
            maxAgeSeconds = jwtProperties.accessTokenExpirationSeconds,
        )

    fun createRefreshTokenCookie(token: String): ResponseCookie =
        createCookie(
            name = REFRESH_TOKEN_COOKIE,
            value = token,
            path = REFRESH_PATH,
            maxAgeSeconds = jwtProperties.refreshTokenExpirationSeconds,
        )

    fun expireAccessTokenCookie(): ResponseCookie =
        createCookie(
            name = ACCESS_TOKEN_COOKIE,
            value = "",
            path = ROOT_PATH,
            maxAgeSeconds = 0,
        )

    fun expireRefreshTokenCookie(): ResponseCookie =
        createCookie(
            name = REFRESH_TOKEN_COOKIE,
            value = "",
            path = REFRESH_PATH,
            maxAgeSeconds = 0,
        )

    private fun createCookie(
        name: String,
        value: String,
        path: String,
        maxAgeSeconds: Long,
    ): ResponseCookie =
        ResponseCookie
            .from(name, value)
            .httpOnly(true)
            .secure(true)
            .sameSite(SAME_SITE_STRICT)
            .path(path)
            .maxAge(maxAgeSeconds)
            .build()
}
