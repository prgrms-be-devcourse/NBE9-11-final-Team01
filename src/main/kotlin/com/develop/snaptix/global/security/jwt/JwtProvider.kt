package com.develop.snaptix.global.security.jwt

import com.develop.snaptix.domain.user.entity.UserRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

private const val ROLE_CLAIM = "role"

@Component
class JwtProvider(
    private val jwtProperties: JwtProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signingKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())
    }

    fun createAccessToken(
        userId: Long,
        role: UserRole,
    ): String =
        createToken(
            userId = userId,
            role = role,
            expiresInSeconds = jwtProperties.accessTokenExpirationSeconds,
        )

    fun createRefreshToken(
        userId: Long,
        role: UserRole,
    ): String =
        createToken(
            userId = userId,
            role = role,
            expiresInSeconds = jwtProperties.refreshTokenExpirationSeconds,
        )

    fun isValid(token: String): Boolean =
        runCatching {
            parseClaims(token)
        }.isSuccess

    fun getUserId(token: String): Long = parseClaims(token).subject.toLong()

    fun getRole(token: String): UserRole = UserRole.valueOf(parseClaims(token)[ROLE_CLAIM, String::class.java])

    private fun createToken(
        userId: Long,
        role: UserRole,
        expiresInSeconds: Long,
    ): String {
        val now = Instant.now(clock)
        val expiration = now.plusSeconds(expiresInSeconds)

        return Jwts
            .builder()
            .subject(userId.toString())
            .claim(ROLE_CLAIM, role.name)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiration))
            .signWith(signingKey)
            .compact()
    }

    private fun parseClaims(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(signingKey)
            .clock { Date.from(Instant.now(clock)) }
            .build()
            .parseSignedClaims(token)
            .payload
}
