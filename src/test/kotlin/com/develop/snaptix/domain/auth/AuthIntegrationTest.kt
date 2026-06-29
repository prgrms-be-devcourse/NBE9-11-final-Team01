package com.develop.snaptix.domain.auth

import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.global.exception.ErrorCode
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

private const val TEST_EMAIL = "integration-login@example.com"
private const val TEST_PASSWORD = "SecurePass123!"
private const val PROTECTED_RESPONSE = "authenticated"

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
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
                    jsonPath("$.role") { value("USER") }
                    jsonPath("$.accessToken") { doesNotExist() }
                    jsonPath("$.refreshToken") { doesNotExist() }
                }.andReturn()

        val loginCookies = loginResult.response.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(loginCookies).hasSize(2)
        assertThat(loginCookies).anySatisfy {
            assertThat(it).contains("accessToken=").contains("HttpOnly")
        }
        assertThat(loginCookies).anySatisfy {
            assertThat(it).contains("refreshToken=").contains("HttpOnly")
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
            assertThat(it).contains("accessToken=").contains("Max-Age=0")
        }
        assertThat(logoutCookies).anySatisfy {
            assertThat(it).contains("refreshToken=").contains("Max-Age=0")
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
            }
    }

    @Test
    fun `로그인 인증 실패 시 401을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"missing@example.com","password":"$TEST_PASSWORD"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.INVALID_LOGIN_CREDENTIALS.code) }
            }
    }

    @Test
    fun `인증 없이 로그아웃 요청 시 401을 반환한다`() {
        mockMvc
            .post("/api/v1/auth/logout")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
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
}
