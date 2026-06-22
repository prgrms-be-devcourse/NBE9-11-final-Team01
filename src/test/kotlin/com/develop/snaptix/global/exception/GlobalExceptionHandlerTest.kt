package com.develop.snaptix.global.exception

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * GlobalExceptionHandler 단위 테스트
 *
 * standaloneSetup 방식 — Spring Context / TestContainers 없이 빠르게 실행.
 * 각 예외 타입별로 올바른 HTTP 상태와 ErrorResponse(code, message, errors)가 반환되는지 검증.
 */
class GlobalExceptionHandlerTest {
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(TestController())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    // ── 예외를 직접 발생시키는 테스트 전용 컨트롤러 ──────────────

    @RestController
    @Validated
    class TestController {
        @GetMapping("/test/business")
        fun throwBusiness(): Nothing = throw BusinessException(ErrorCode.TICKET_NOT_FOUND)

        @GetMapping("/test/business-custom")
        fun throwBusinessCustom(): Nothing = throw BusinessException(ErrorCode.TICKET_NOT_FOUND, "커스텀 메시지입니다.")

        /** @Valid + @RequestBody 검증 실패 → MethodArgumentNotValidException */
        @PostMapping("/test/validation")
        fun validateBody(
            @Valid @RequestBody body: SampleRequest,
        ): String = body.name.orEmpty()

        /** consumes 제한 → HttpMediaTypeNotSupportedException */
        @PostMapping("/test/media-type", consumes = ["application/json"])
        fun requireJson(
            @RequestBody body: SampleRequest,
        ): String = body.name.orEmpty()

        /** Int 파라미터에 문자열 전달 → MethodArgumentTypeMismatchException */
        @GetMapping("/test/type-mismatch")
        fun typeMismatch(
            @RequestParam id: Int,
        ): String = "$id"

        /** required 파라미터 누락 → MissingServletRequestParameterException */
        @GetMapping("/test/missing-param")
        fun missingParam(
            @RequestParam(required = true) name: String,
        ): String = name

        /** NoResourceFoundException 직접 발생 */
        @GetMapping("/test/no-resource")
        fun noResource(): Nothing = throw NoResourceFoundException(
            HttpMethod.GET,
            "/test/no-resource",
            "/static/missing.js",
        )

        /** DataIntegrityViolationException 직접 발생 */
        @GetMapping("/test/data-integrity")
        fun dataIntegrity(): Nothing = throw DataIntegrityViolationException("Duplicate entry 'x' for key 'uk_email'")

        /** 처리되지 않은 예외 Fallback */
        @GetMapping("/test/internal-error")
        fun internalError(): Nothing = throw IllegalStateException("예상치 못한 서버 오류")
    }

    data class SampleRequest(
        @field:NotBlank(message = "이름은 필수입니다.")
        val name: String?,
    )

    // ── BusinessException ─────────────────────────────────────

    @Nested
    inner class BusinessExceptionTest {
        @Test
        fun `ErrorCode의 HTTP 상태와 code가 그대로 응답된다`() {
            mockMvc
                .get("/test/business")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value(ErrorCode.TICKET_NOT_FOUND.code) }
                    jsonPath("$.message") { value(ErrorCode.TICKET_NOT_FOUND.message) }
                    jsonPath("$.errors") { value(null) }
                }
        }

        @Test
        fun `커스텀 메시지가 message 필드에 반영된다`() {
            mockMvc
                .get("/test/business-custom")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value(ErrorCode.TICKET_NOT_FOUND.code) }
                    jsonPath("$.message") { value("커스텀 메시지입니다.") }
                }
        }
    }

    // ── Validation ────────────────────────────────────────────

    @Nested
    inner class ValidationTest {
        @Test
        fun `@Valid 실패 시 VALIDATION_FAILED와 errors 배열이 반환된다`() {
            mockMvc
                .post("/test/validation") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name": ""}"""
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
                    jsonPath("$.errors") { isArray() }
                    jsonPath("$.errors[0].field") { value("name") }
                    jsonPath("$.errors[0].reason") { value("이름은 필수입니다.") }
                }
        }
    }

    // ── Request Parameter ─────────────────────────────────────

    @Nested
    inner class RequestParameterTest {
        @Test
        fun `Int 파라미터에 문자열 전달 시 TYPE_MISMATCH가 반환된다`() {
            mockMvc
                .get("/test/type-mismatch?id=not-a-number")
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(ErrorCode.TYPE_MISMATCH.code) }
                }
        }

        @Test
        fun `필수 파라미터 누락 시 PARAM_MISSING이 반환된다`() {
            mockMvc
                .get("/test/missing-param")
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(ErrorCode.PARAM_MISSING.code) }
                }
        }
    }

    // ── HTTP 예외 ─────────────────────────────────────────────

    @Nested
    inner class HttpExceptionTest {
        @Test
        fun `NoResourceFoundException 발생 시 NOT_FOUND가 반환된다`() {
            mockMvc
                .get("/test/no-resource")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value(ErrorCode.NOT_FOUND.code) }
                }
        }

        @Test
        fun `GET 전용 엔드포인트에 POST 요청 시 METHOD_NOT_ALLOWED가 반환된다`() {
            mockMvc
                .post("/test/business") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect {
                    status { isMethodNotAllowed() }
                    jsonPath("$.code") { value(ErrorCode.METHOD_NOT_ALLOWED.code) }
                }
        }

        @Test
        fun `지원하지 않는 Content-Type 전송 시 UNSUPPORTED_MEDIA_TYPE이 반환된다`() {
            mockMvc
                .post("/test/media-type") {
                    contentType = MediaType.TEXT_PLAIN
                    content = "plain text body"
                }.andExpect {
                    status { isUnsupportedMediaType() }
                    jsonPath("$.code") { value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code) }
                }
        }
    }

    // ── DB 예외 ───────────────────────────────────────────────

    @Nested
    inner class DatabaseExceptionTest {
        @Test
        fun `DataIntegrityViolationException 발생 시 DUPLICATE_RESOURCE가 반환된다`() {
            mockMvc
                .get("/test/data-integrity")
                .andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value(ErrorCode.DUPLICATE_RESOURCE.code) }
                }
        }
    }

    // ── Fallback ──────────────────────────────────────────────

    @Nested
    inner class FallbackExceptionTest {
        @Test
        fun `처리되지 않은 예외 발생 시 INTERNAL_SERVER_ERROR가 반환된다`() {
            mockMvc
                .get("/test/internal-error")
                .andExpect {
                    status { isInternalServerError() }
                    jsonPath("$.code") { value(ErrorCode.INTERNAL_SERVER_ERROR.code) }
                    jsonPath("$.errors") { value(null) }
                }
        }
    }
}
