package com.develop.snaptix.global.security.config

import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.security.handler.CustomAccessDeniedHandler
import com.develop.snaptix.global.security.handler.CustomAuthenticationEntryPoint
import com.develop.snaptix.global.security.handler.SecurityErrorResponseWriter
import com.develop.snaptix.global.security.jwt.JwtAuthenticationFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(
    controllers = [SecurityTestController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [JwtAuthenticationFilter::class],
        ),
    ],
)
@Import(
    SecurityConfig::class,
    CustomAuthenticationEntryPoint::class,
    CustomAccessDeniedHandler::class,
    SecurityErrorResponseWriter::class,
)
class SecurityConfigTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `public endpoint 는 인증 없이 접근 가능`() {
        mockMvc
            .get("/api/v1/events/test")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `admin 은 admin endpoint 접근 가능`() {
        mockMvc
            .get("/api/v1/admin/test") {
                with(user("admin").roles("ADMIN"))
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `user 는 admin endpoint 접근 불가`() {
        mockMvc
            .get("/api/v1/admin/test") {
                with(user("user").roles("USER"))
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ACCESS_DENIED.code) }
                jsonPath("$.message") { value(ErrorCode.ACCESS_DENIED.message) }
            }
    }

    @Test
    fun `staff 는 staff endpoint 접근 가능`() {
        mockMvc
            .get("/api/v1/staff/test") {
                with(user("staff").roles("STAFF"))
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `user 는 staff endpoint 접근 불가`() {
        mockMvc
            .get("/api/v1/staff/test") {
                with(user("user").roles("USER"))
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ACCESS_DENIED.code) }
                jsonPath("$.message") { value(ErrorCode.ACCESS_DENIED.message) }
            }
    }

    @Test
    fun `이벤트 하위 조회 endpoint 는 인증 없이 접근 가능`() {
        mockMvc
            .get("/api/v1/events/test/zones")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `GET 이외 이벤트 endpoint 는 인증 없이 접근 불가`() {
        mockMvc
            .post("/api/v1/events")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
                jsonPath("$.message") { value(ErrorCode.UNAUTHORIZED.message) }
            }
    }
}
