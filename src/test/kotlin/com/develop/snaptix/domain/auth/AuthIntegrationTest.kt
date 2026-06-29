package com.develop.snaptix.domain.auth

import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.security.jwt.JwtProperties
import com.develop.snaptix.global.security.jwt.JwtProvider
import com.develop.snaptix.support.IntegrationTestSupport
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val TEST_EMAIL = "integration-login@example.com"
private const val TEST_PASSWORD = "SecurePass123!"
private const val PROTECTED_RESPONSE = "authenticated"

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jwtProperties: JwtProperties,
) : IntegrationTestSupport() {
    @TestConfiguration
    class TestProtectedControllerConfig {
        @Bean
        fun testProtectedController(): TestProtectedController = TestProtectedController()
    }

    @RestController
    class TestProtectedController {
        @GetMapping("/api/v1/auth-test/protected")
        fun protected(): String = PROTECTED_RESPONSE
    }

    @BeforeEach
    fun setUp() {
        transaction {
            UsersTable.deleteAll()
        }
    }

    @Test
    fun `회원가입 후 로그인하면 JWT 쿠키가 발급되고 로그아웃 시 쿠키가 만료된다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$TEST_EMAIL","password":"$TEST_PASSWORD"}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.email") { value(TEST_EMAIL) }
            }

        val loginResult =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"$TEST_EMAIL","password":"$TEST_PASSWORD"}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.userId") { exists() }
                    jsonPath("$.role") { value("USER") }
                    jsonPath("$.accessToken") { doesNotExist() }
                    jsonPath("$.refreshToken") { doesNotExist() }
                }.andReturn()

        val loginCookies = loginResult.response.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(loginCookies).hasSize(2)
        assertThat(loginCookies).anySatisfy {
            assertThat(it)
                .contains("accessToken=")
                .contains("Path=/")
                .contains("Max-Age=600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
        }
        assertThat(loginCookies).anySatisfy {
            assertThat(it)
                .contains("refreshToken=")
                .contains("Path=/api/v1/auth/refresh")
                .contains("Max-Age=604800")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
        }

        val logoutResult =
            mockMvc
                .post("/api/v1/auth/logout") {
                    cookie(loginCookies.toRequestCookie("accessToken"))
                    cookie(loginCookies.toRequestCookie("refreshToken"))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.message") { value("로그아웃이 성공적으로 처리되었습니다.") }
                }.andReturn()

        val logoutCookies = logoutResult.response.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(logoutCookies).hasSize(2)
        assertThat(logoutCookies).anySatisfy {
            assertThat(it)
                .contains("accessToken=")
                .contains("Path=/")
                .contains("Max-Age=0")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
        }
        assertThat(logoutCookies).anySatisfy {
            assertThat(it)
                .contains("refreshToken=")
                .contains("Path=/api/v1/auth/refresh")
                .contains("Max-Age=0")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
        }

        mockMvc
            .get("/api/v1/auth-test/protected") {
                cookie(logoutCookies.toRequestCookie("accessToken"))
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
                jsonPath("$.message") { value(ErrorCode.UNAUTHORIZED.message) }
                jsonPath("$.errors") { value(null) }
            }
    }

    @Test
    fun `로그인 후 accessToken 쿠키로 인증 필요한 API에 접근할 수 있다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$TEST_EMAIL","password":"$TEST_PASSWORD"}"""
            }.andExpect {
                status { isCreated() }
            }

        val loginResult =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"$TEST_EMAIL","password":"$TEST_PASSWORD"}"""
                }.andExpect {
                    status { isOk() }
                }.andReturn()

        val loginCookies = loginResult.response.getHeaders(HttpHeaders.SET_COOKIE)

        mockMvc
            .get("/api/v1/auth-test/protected") {
                cookie(loginCookies.toRequestCookie("accessToken"))
            }.andExpect {
                status { isOk() }
                content { string(PROTECTED_RESPONSE) }
            }
    }

    @Test
    fun `JWT 없이 인증 필요한 API 접근 시 401을 반환한다`() {
        mockMvc
            .get("/api/v1/auth-test/protected")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
                jsonPath("$.message") { value(ErrorCode.UNAUTHORIZED.message) }
                jsonPath("$.errors") { value(null) }
            }
    }

    @Test
    fun `유효하지 않은 accessToken 쿠키로 인증 필요한 API 접근 시 401을 반환한다`() {
        mockMvc
            .get("/api/v1/auth-test/protected") {
                cookie(Cookie("accessToken", "invalid-token"))
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.TOKEN_INVALID.code) }
                jsonPath("$.message") { value(ErrorCode.TOKEN_INVALID.message) }
                jsonPath("$.errors") { value(null) }
            }
    }

    @Test
    fun `중복 이메일로 회원가입하면 409를 반환한다`() {
        val requestBody = """{"email":"$TEST_EMAIL","password":"$TEST_PASSWORD"}"""

        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = requestBody
            }.andExpect {
                status { isCreated() }
            }

        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = requestBody
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value(ErrorCode.DUPLICATE_EMAIL.code) }
                jsonPath("$.message") { value(ErrorCode.DUPLICATE_EMAIL.message) }
            }
    }

    @Test
    fun `만료된 accessToken 쿠키로 인증 필요한 API 접근 시 401을 반환한다`() {
        mockMvc
            .get("/api/v1/auth-test/protected") {
                cookie(Cookie("accessToken", expiredAccessToken()))
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.TOKEN_EXPIRED.code) }
                jsonPath("$.message") { value(ErrorCode.TOKEN_EXPIRED.message) }
                jsonPath("$.errors") { value(null) }
            }
    }

    @Test
    fun `회원가입 이메일 형식이 올바르지 않으면 400을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"invalid-email","password":"$TEST_PASSWORD"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors[0].field") { value("email") }
            }
    }

    @Test
    fun `회원가입 비밀번호가 8자 미만이면 400을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"short-password@example.com","password":"Pass1!"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors[0].field") { value("password") }
            }
    }

    @Test
    fun `회원가입 비밀번호에 특수문자가 없으면 400을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"invalid-password@example.com","password":"SecurePass123"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors[0].field") { value("password") }
            }
    }

    @Test
    fun `로그인 비밀번호가 일치하지 않으면 401을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$TEST_EMAIL","password":"$TEST_PASSWORD"}"""
            }.andExpect {
                status { isCreated() }
            }

        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$TEST_EMAIL","password":"WrongPass123!"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.INVALID_LOGIN_CREDENTIALS.code) }
                jsonPath("$.message") { value(ErrorCode.INVALID_LOGIN_CREDENTIALS.message) }
                jsonPath("$.errors") { value(null) }
            }
    }

    @Test
    fun `로그인 이메일 형식이 올바르지 않으면 400을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"invalid-email","password":"$TEST_PASSWORD"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                jsonPath("$.errors[0].field") { value("email") }
            }
    }

    @Test
    fun `인증 없이 로그아웃 요청 시 401을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/logout")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
                jsonPath("$.message") { value(ErrorCode.UNAUTHORIZED.message) }
                jsonPath("$.errors") { value(null) }
            }
    }

    private fun List<String>.toRequestCookie(name: String): Cookie {
        val header =
            firstOrNull { it.startsWith("$name=") }
                ?: error("Set-Cookie 헤더에서 '$name' 쿠키를 찾을 수 없습니다")
        val value =
            header
                .substringAfter("$name=")
                .substringBefore(";")

        return Cookie(name, value)
    }

    private fun expiredAccessToken(): String {
        val issuedAt = Instant.now().minusSeconds(jwtProperties.accessTokenExpirationSeconds + 1)
        return JwtProvider(
            jwtProperties = jwtProperties,
            clock = Clock.fixed(issuedAt, ZoneOffset.UTC),
        ).createAccessToken(userId = 1L, role = UserRole.USER)
    }
}
