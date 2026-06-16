package com.develop.snaptix.domain.auth.service

import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.repository.AuthUserRepository
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
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
    private val authService = AuthService(authUserRepository, passwordEncoder)

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
}
