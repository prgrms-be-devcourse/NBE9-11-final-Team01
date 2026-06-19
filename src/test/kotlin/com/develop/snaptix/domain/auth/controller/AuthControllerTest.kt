package com.develop.snaptix.domain.auth.controller

import com.develop.snaptix.domain.auth.dto.LoginRequest
import com.develop.snaptix.domain.auth.dto.LoginResponse
import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.dto.SignUpResponse
import com.develop.snaptix.domain.auth.service.AuthService
import com.develop.snaptix.domain.auth.service.LoginResult
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.GlobalExceptionHandler
import com.develop.snaptix.global.security.jwt.CookieProvider
import com.develop.snaptix.global.security.jwt.JwtProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AuthControllerTest {
    private val authService = mockk<AuthService>()
    private val jwtProperties =
        JwtProperties().apply {
            secret = "test-secret-key-for-snaptix-login-controller-256-bit"
            accessTokenExpirationSeconds = 600
            refreshTokenExpirationSeconds = 604_800
        }
    private val cookieProvider = CookieProvider(jwtProperties)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(AuthController(authService, cookieProvider))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `회원가입 성공 시 201과 생성된 사용자 정보를 반환한다`() {
        every {
            authService.signUp(SignUpRequest("user@example.com", "SecurePass123!"))
        } returns SignUpResponse(userId = 1L, email = "user@example.com")

        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"user@example.com","password":"SecurePass123!"}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.userId") { value(1) }
                jsonPath("$.email") { value("user@example.com") }
            }
    }

    @Test
    fun `회원가입 요청 값이 유효하지 않으면 400을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"invalid-email","password":"short"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors") { isArray() }
            }
    }

    @Test
    fun `중복 이메일이면 409를 반환한다`() {
        every {
            authService.signUp(SignUpRequest("user@example.com", "SecurePass123!"))
        } throws BusinessException(ErrorCode.DUPLICATE_EMAIL)

        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"user@example.com","password":"SecurePass123!"}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value(ErrorCode.DUPLICATE_EMAIL.code) }
                jsonPath("$.message") { value(ErrorCode.DUPLICATE_EMAIL.message) }
            }
    }

    @Test
    fun `로그인 성공 시 200과 인증 쿠키를 반환한다`() {
        every {
            authService.login(LoginRequest("user@example.com", "SecurePass123!"))
        } returns
            LoginResult(
                response = LoginResponse(userId = 1L, role = UserRole.USER),
                accessToken = "access-token",
                refreshToken = "refresh-token",
            )

        val result =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"user@example.com","password":"SecurePass123!"}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.userId") { value(1) }
                    jsonPath("$.role") { value("USER") }
                    jsonPath("$.accessToken") { doesNotExist() }
                    jsonPath("$.refreshToken") { doesNotExist() }
                }.andReturn()

        val setCookieHeaders = result.response.getHeaders("Set-Cookie")
        assertThat(setCookieHeaders).hasSize(2)
        assertThat(setCookieHeaders).anySatisfy {
            assertThat(it).contains("accessToken=access-token")
        }
        assertThat(setCookieHeaders).anySatisfy {
            assertThat(it).contains("refreshToken=refresh-token")
        }
    }

    @Test
    fun `로그인 요청 값이 유효하지 않으면 400을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"invalid-email","password":""}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors") { isArray() }
            }
    }

    @Test
    fun `로그인 인증 실패 시 401을 반환한다`() {
        every {
            authService.login(LoginRequest("user@example.com", "WrongPass123!"))
        } throws BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS)

        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"user@example.com","password":"WrongPass123!"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.INVALID_LOGIN_CREDENTIALS.code) }
                jsonPath("$.message") { value(ErrorCode.INVALID_LOGIN_CREDENTIALS.message) }
            }
    }

    @Test
    fun `로그아웃 성공 시 인증 쿠키를 만료시킨다`() {
        val result =
            mockMvc
                .post("/api/v1/auth/logout")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.message") { value("로그아웃이 성공적으로 처리되었습니다.") }
                }.andReturn()

        val setCookieHeaders = result.response.getHeaders("Set-Cookie")
        assertThat(setCookieHeaders).hasSize(2)
        assertThat(setCookieHeaders).anySatisfy {
            assertThat(it)
                .contains("accessToken=")
                .contains("Max-Age=0")
                .contains("Path=/")
        }
        assertThat(setCookieHeaders).anySatisfy {
            assertThat(it)
                .contains("refreshToken=")
                .contains("Max-Age=0")
                .contains("Path=/api/v1/auth/refresh")
        }
    }
}
