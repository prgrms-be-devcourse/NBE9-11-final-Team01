package com.develop.snaptix.domain.auth.controller

import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.dto.SignUpResponse
import com.develop.snaptix.domain.auth.service.AuthService
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AuthControllerTest {
    private val authService = mockk<AuthService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(AuthController(authService))
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
}
