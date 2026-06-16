package com.develop.snaptix.domain.auth.service

import com.develop.snaptix.domain.auth.dto.LoginRequest
import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.repository.AuthUserRecord
import com.develop.snaptix.domain.auth.repository.AuthUserRepository
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.security.jwt.JwtProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder

class AuthServiceTest {
    private val authUserRepository = mockk<AuthUserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val jwtProvider = mockk<JwtProvider>()
    private val authService = AuthService(authUserRepository, passwordEncoder, jwtProvider)

    @Test
    fun `회원가입 성공 시 비밀번호를 암호화하고 USER 권한으로 저장한다`() {
        val request = SignUpRequest(email = "user@example.com", password = "SecurePass123!")
        every { authUserRepository.existsByEmail(request.email) } returns false
        every { passwordEncoder.encode(request.password) } returns "encoded-password"
        every { authUserRepository.saveUser(request.email, "encoded-password", UserRole.USER) } returns 1L

        val response = authService.signUp(request)

        assertThat(response.userId).isEqualTo(1L)
        assertThat(response.email).isEqualTo(request.email)
        verify(exactly = 1) { passwordEncoder.encode(request.password) }
        verify(exactly = 1) { authUserRepository.saveUser(request.email, "encoded-password", UserRole.USER) }
    }

    @Test
    fun `이미 존재하는 이메일이면 DUPLICATE_EMAIL 예외가 발생한다`() {
        val request = SignUpRequest(email = "user@example.com", password = "SecurePass123!")
        every { authUserRepository.existsByEmail(request.email) } returns true

        val exception =
            assertThrows(BusinessException::class.java) {
                authService.signUp(request)
            }

        assertThat(exception.httpStatus).isEqualTo(ErrorCode.DUPLICATE_EMAIL.status)
    }

    @Test
    fun `로그인 성공 시 access token과 refresh token을 생성한다`() {
        val request = LoginRequest(email = "user@example.com", password = "SecurePass123!")
        val user =
            AuthUserRecord(
                id = 1L,
                email = request.email,
                password = "encoded-password",
                role = UserRole.USER,
            )
        every { authUserRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.password) } returns true
        every { jwtProvider.createAccessToken(user.id, user.role) } returns "access-token"
        every { jwtProvider.createRefreshToken(user.id, user.role) } returns "refresh-token"

        val result = authService.login(request)

        assertThat(result.response.userId).isEqualTo(user.id)
        assertThat(result.response.role).isEqualTo(UserRole.USER)
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
    }

    @Test
    fun `존재하지 않는 이메일이면 INVALID_LOGIN_CREDENTIALS 예외가 발생한다`() {
        val request = LoginRequest(email = "missing@example.com", password = "SecurePass123!")
        every { authUserRepository.findByEmail(request.email) } returns null

        val exception =
            assertThrows(BusinessException::class.java) {
                authService.login(request)
            }

        assertThat(exception.httpStatus).isEqualTo(ErrorCode.INVALID_LOGIN_CREDENTIALS.status)
    }

    @Test
    fun `비밀번호가 일치하지 않으면 INVALID_LOGIN_CREDENTIALS 예외가 발생한다`() {
        val request = LoginRequest(email = "user@example.com", password = "WrongPass123!")
        val user =
            AuthUserRecord(
                id = 1L,
                email = request.email,
                password = "encoded-password",
                role = UserRole.USER,
            )
        every { authUserRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.password) } returns false

        val exception =
            assertThrows(BusinessException::class.java) {
                authService.login(request)
            }

        assertThat(exception.httpStatus).isEqualTo(ErrorCode.INVALID_LOGIN_CREDENTIALS.status)
    }
}
