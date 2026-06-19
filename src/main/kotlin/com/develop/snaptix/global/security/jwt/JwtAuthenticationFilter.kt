package com.develop.snaptix.global.security.jwt

import com.develop.snaptix.global.security.auth.AuthenticatedUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val ACCESS_TOKEN_COOKIE_NAME = "accessToken"
private const val ROLE_PREFIX = "ROLE_"

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val accessToken = request.extractAccessToken()

        if (accessToken != null && jwtProvider.isValid(accessToken)) {
            SecurityContextHolder.getContext().authentication = createAuthentication(accessToken)
        }

        filterChain.doFilter(request, response)
    }

    private fun createAuthentication(token: String): UsernamePasswordAuthenticationToken {
        val role = jwtProvider.getRole(token)
        val principal =
            AuthenticatedUser(
                userId = jwtProvider.getUserId(token),
                role = role,
            )

        return UsernamePasswordAuthenticationToken(
            principal,
            null,
            listOf(SimpleGrantedAuthority("$ROLE_PREFIX${role.name}")),
        )
    }

    private fun HttpServletRequest.extractAccessToken(): String? =
        cookies
            ?.firstOrNull { it.name == ACCESS_TOKEN_COOKIE_NAME }
            ?.value
            ?.takeIf { it.isNotBlank() }
}
