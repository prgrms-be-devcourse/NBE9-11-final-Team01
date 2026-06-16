package com.develop.snaptix.global.security.handler

import com.develop.snaptix.global.exception.ErrorCode
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private const val UTF_8 = "UTF-8"

@Component
class SecurityErrorResponseWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        response: HttpServletResponse,
        errorCode: ErrorCode,
    ) {
        response.status = errorCode.status.value()
        response.characterEncoding = UTF_8
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.writer, errorCode.toErrorResponse())
    }
}
