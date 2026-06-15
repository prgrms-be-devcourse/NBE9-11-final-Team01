package com.develop.snaptix.global.security.jwt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val TEST_SECRET = "test-secret-key-for-snaptix-cookie-provider-256-bit"

class CookieProviderTest {
    private val properties =
        JwtProperties().apply {
            secret = TEST_SECRET
            accessTokenExpirationSeconds = 600
            refreshTokenExpirationSeconds = 604_800
        }

    private val cookieProvider = CookieProvider(properties)

    @Test
    fun `access token cookie는 보안 속성과 만료 시간을 포함한다`() {
        val cookie = cookieProvider.createAccessTokenCookie("access-token")

        assertThat(cookie.name).isEqualTo("accessToken")
        assertThat(cookie.value).isEqualTo("access-token")
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.maxAge.seconds).isEqualTo(600)
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.isSecure).isTrue()
        assertThat(cookie.sameSite).isEqualTo("Strict")
    }

    @Test
    fun `refresh token cookie는 refresh path와 만료 시간을 포함한다`() {
        val cookie = cookieProvider.createRefreshTokenCookie("refresh-token")

        assertThat(cookie.name).isEqualTo("refreshToken")
        assertThat(cookie.value).isEqualTo("refresh-token")
        assertThat(cookie.path).isEqualTo("/api/v1/auth/refresh")
        assertThat(cookie.maxAge.seconds).isEqualTo(604_800)
    }

    @Test
    fun `만료 cookie는 maxAge가 0이다`() {
        val accessCookie = cookieProvider.expireAccessTokenCookie()
        val refreshCookie = cookieProvider.expireRefreshTokenCookie()

        assertThat(accessCookie.maxAge.seconds).isZero()
        assertThat(refreshCookie.maxAge.seconds).isZero()
    }
}
