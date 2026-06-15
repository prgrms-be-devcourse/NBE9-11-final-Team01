package com.develop.snaptix.global.filter

import global.filter.TraceIdFilter
import global.filter.TraceIdFilter.Companion.TRACE_ID_HEADER
import global.filter.TraceIdFilter.Companion.TRACE_ID_KEY
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TraceIdFilterTest {
    private lateinit var filter: TraceIdFilter

    @BeforeEach
    fun setUp() {
        filter = TraceIdFilter()
        MDC.clear()
    }

    @Test
    fun `X-Trace-Id 헤더가 없으면 UUID를 생성하여 응답 헤더에 설정한다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        val responseTraceId = response.getHeader(TRACE_ID_HEADER)
        assertThat(responseTraceId).isNotNull()
        assertThat(responseTraceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    @Test
    fun `X-Trace-Id 헤더가 있으면 해당 값을 traceId로 사용한다`() {
        val givenTraceId = "external-trace-id-abc123"
        val request =
            MockHttpServletRequest().apply {
                addHeader(TRACE_ID_HEADER, givenTraceId)
            }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(givenTraceId)
    }

    @Test
    fun `필터 실행 중 MDC에 traceId가 설정된다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var capturedTraceId: String? = null

        filter.doFilter(
            request,
            response,
            FilterChain { _, _ ->
                capturedTraceId = MDC.get(TRACE_ID_KEY)
            },
        )

        assertThat(capturedTraceId).isNotNull()
        assertThat(capturedTraceId).isEqualTo(response.getHeader(TRACE_ID_HEADER))
    }

    @Test
    fun `필터 완료 후 MDC에서 traceId가 제거된다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertThat(MDC.get(TRACE_ID_KEY)).isNull()
    }

    @Test
    fun `FilterChain에서 예외가 발생해도 MDC가 정리된다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        runCatching {
            filter.doFilter(
                request,
                response,
                FilterChain { _, _ ->
                    throw IllegalStateException("의도된 테스트 예외")
                },
            )
        }

        assertThat(MDC.get(TRACE_ID_KEY)).isNull()
    }

    @Test
    fun `공백만 있는 X-Trace-Id 헤더는 무시하고 새 UUID를 생성한다`() {
        val request =
            MockHttpServletRequest().apply {
                addHeader(TRACE_ID_HEADER, "   ")
            }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        val responseTraceId = response.getHeader(TRACE_ID_HEADER)
        assertThat(responseTraceId).isNotBlank()
        assertThat(responseTraceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
