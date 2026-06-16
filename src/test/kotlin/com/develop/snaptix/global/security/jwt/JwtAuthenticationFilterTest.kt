package com.develop.snaptix.global.security.jwt

import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.security.auth.AuthenticatedUser
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

private const val FILTER_TEST_SECRET = "test-secret-key-for-snaptix-jwt-filter-256-bit"

class JwtAuthenticationFilterTest {
    private val jwtProperties =
        JwtProperties().apply {
            secret = FILTER_TEST_SECRET
            accessTokenExpirationSeconds = 600
            refreshTokenExpirationSeconds = 604_800
        }

    private val jwtProvider = JwtProvider(jwtProperties)
    private val filter = JwtAuthenticationFilter(jwtProvider)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `accessToken cookie가 유효하면 SecurityContext에 인증 정보를 저장한다`() {
        val token = jwtProvider.createAccessToken(userId = 1L, role = UserRole.USER)
        val request = MockHttpServletRequest().apply { setCookies(Cookie("accessToken", token)) }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: error("Authentication should be stored in SecurityContext")
        val principal = authentication.principal as AuthenticatedUser
        assertThat(principal.userId).isEqualTo(1L)
        assertThat(principal.role).isEqualTo(UserRole.USER)
        assertThat(authentication.authorities.map { it.authority }).containsExactly("ROLE_USER")
    }

    @Test
    fun `ADMIN accessToken cookie가 유효하면 ROLE_ADMIN 권한을 저장한다`() {
        val token = jwtProvider.createAccessToken(userId = 2L, role = UserRole.ADMIN)
        val request = MockHttpServletRequest().apply { setCookies(Cookie("accessToken", token)) }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: error("Authentication should be stored in SecurityContext")
        val principal = authentication.principal as AuthenticatedUser
        assertThat(principal.userId).isEqualTo(2L)
        assertThat(principal.role).isEqualTo(UserRole.ADMIN)
        assertThat(authentication.authorities.map { it.authority }).containsExactly("ROLE_ADMIN")
    }

    @Test
    fun `accessToken cookie가 없으면 인증 정보를 저장하지 않는다`() {
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `accessToken cookie가 유효하지 않으면 인증 정보를 저장하지 않는다`() {
        val request = MockHttpServletRequest().apply { setCookies(Cookie("accessToken", "invalid-token")) }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }
}
