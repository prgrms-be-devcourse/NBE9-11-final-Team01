package com.develop.snaptix.global.security.jwt

import com.develop.snaptix.domain.user.entity.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val JWT_TEST_SECRET = "test-secret-key-for-snaptix-jwt-provider-256-bit"
private const val ACCESS_TOKEN_EXPIRATION_SECONDS = 600L
private const val REFRESH_TOKEN_EXPIRATION_SECONDS = 604_800L

class JwtProviderTest {
    private val properties =
        JwtProperties().apply {
            secret = JWT_TEST_SECRET
            accessTokenExpirationSeconds = ACCESS_TOKEN_EXPIRATION_SECONDS
            refreshTokenExpirationSeconds = REFRESH_TOKEN_EXPIRATION_SECONDS
        }

    private val jwtProvider =
        JwtProvider(
            jwtProperties = properties,
            clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC),
        )

    @Test
    fun `access token 생성 후 userId와 role을 추출할 수 있다`() {
        val token = jwtProvider.createAccessToken(userId = 1L, role = UserRole.ADMIN)

        assertThat(jwtProvider.isValid(token)).isTrue()
        assertThat(jwtProvider.validate(token)).isEqualTo(JwtValidationStatus.VALID)
        assertThat(jwtProvider.getUserId(token)).isEqualTo(1L)
        assertThat(jwtProvider.getRole(token)).isEqualTo(UserRole.ADMIN)
    }

    @Test
    fun `refresh token 생성 후 userId와 role을 추출할 수 있다`() {
        val token = jwtProvider.createRefreshToken(userId = 2L, role = UserRole.USER)

        assertThat(jwtProvider.isValid(token)).isTrue()
        assertThat(jwtProvider.getUserId(token)).isEqualTo(2L)
        assertThat(jwtProvider.getRole(token)).isEqualTo(UserRole.USER)
    }

    @Test
    fun `잘못된 token은 유효하지 않다`() {
        assertThat(jwtProvider.isValid("invalid-token")).isFalse()
        assertThat(jwtProvider.validate("invalid-token")).isEqualTo(JwtValidationStatus.INVALID)
    }

    @Test
    fun `만료된 token은 EXPIRED 상태를 반환한다`() {
        val token = jwtProvider.createAccessToken(userId = 3L, role = UserRole.USER)
        val expiredJwtProvider =
            JwtProvider(
                jwtProperties = properties,
                clock = Clock.fixed(Instant.parse("2026-06-15T00:11:00Z"), ZoneOffset.UTC),
            )

        assertThat(expiredJwtProvider.isValid(token)).isFalse()
        assertThat(expiredJwtProvider.validate(token)).isEqualTo(JwtValidationStatus.EXPIRED)
    }
}
