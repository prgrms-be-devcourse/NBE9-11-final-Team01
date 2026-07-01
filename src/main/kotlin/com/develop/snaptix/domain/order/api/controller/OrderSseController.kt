package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.domain.order.api.controller.docs.OrderSseApiDocs
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper

/**
 * ## SSE 엔드포인트에서 BusinessException 처리 (이슈 #362 서브 이슈)
 * 이 컨트롤러는 `produces = [MediaType.TEXT_EVENT_STREAM_VALUE]`로 매핑되어 있다.
 * 실제 SSE 클라이언트(xk6-sse 등)는 `Accept: text/event-stream`을 고정으로 보내는데
 * (xk6-sse `sse.go` 소스 확인: `req.Header.Set("Accept", "text/event-stream")`,
 * 사용자가 다른 Accept 헤더를 넘기지 않는 한 항상 이 값 하나만 보냄), `connect()`에서
 * `BusinessException`이 발생해 `GlobalExceptionHandler.handleBusinessException()`
 * (JSON 응답)까지 전파되면, 이 요청이 "생산 가능한 타입"을 text/event-stream 하나로
 * 제한하고 있어 JSON 표현과의 협상이 실패하고 `HttpMediaTypeNotAcceptableException`이
 * 새로 발생한다 — 원래 의도한 403/404 등이 클라이언트에 전혀 전달되지 못하는 문제였다
 * (부하 테스트에서 다수 확인: 소유권 위반 시 403 대신 401/응답 붕괴로 관측됨).
 *
 * `GlobalExceptionHandler`의 producible 타입 협상을 우회하지 않는 한, 클라이언트의
 * Accept 헤더가 무엇이든 이 매핑 위에서는 JSON 예외 응답을 만들 수 없다. 그래서
 * `BusinessException`을 여기서 직접 잡아 `HttpServletResponse`에 상태 코드/JSON 바디를
 * 수동으로 써서 완전히 응답한 뒤, 컨트롤러 로컬 [SseResponseAlreadyWrittenException]
 * 마커를 던져 실행을 중단한다. 이 마커는 같은 클래스의 [handleAlreadyWritten]
 * (반환 타입 Unit)으로만 처리되어 `HttpMessageConverter` 협상을 아예 타지 않으므로,
 * 클라이언트의 Accept 헤더와 무관하게 항상 올바르게 응답한다. 컨트롤러 로컬
 * `@ExceptionHandler`는 `@ControllerAdvice`보다 항상 먼저 매칭되므로(Spring MVC 표준
 * 동작), `GlobalExceptionHandler.kt`는 전혀 건드리지 않는다.
 *
 * 참고: `@PreAuthorize("hasRole('USER')")` 실패(`AuthorizationDeniedException`)는
 * 메서드 진입 전 AOP에서 발생하므로 이 처리로 커버되지 않는다. 다만 이번에 실측된
 * 버그는 소유권 검증(`FORBIDDEN_ACCESS`)과 토큰 누락(`TOKEN_MISSING`) 케이스뿐이었고
 * 둘 다 `BusinessException`이라 이번 수정으로 커버된다.
 */
@RestController
@RequestMapping("/api/v1/orders/sse")
class OrderSseController(
    private val sseConnectionManager: SseConnectionManager,
    private val objectMapper: ObjectMapper,
) : OrderSseApiDocs {
    @GetMapping("/{orderId}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @PreAuthorize("hasRole('USER')")
    override fun subscribe(
        @AuthenticationPrincipal(expression = "userId") userId: Long?,
        @PathVariable orderId: String,
    ): SseEmitter {
        val validUserId = userId ?: writeAndAbort(BusinessException(ErrorCode.TOKEN_MISSING))

        return try {
            sseConnectionManager.connect(
                key = SseChannelKey(ORDER_RESOURCE, orderId),
                userId = validUserId.toString(),
            )
        } catch (ex: BusinessException) {
            writeAndAbort(ex)
        }
    }

    /**
     * `BusinessException`을 `HttpServletResponse`에 직접 써서 응답을 완전히 완료하고,
     * [SseResponseAlreadyWrittenException]을 던져 메서드 실행을 중단한다.
     * `GlobalExceptionHandler.handleBusinessException()`과 동일한 응답 형태
     * (상태 코드, `ErrorResponse` JSON 바디, `Retry-After` 헤더)를 그대로 재현한다.
     *
     * 메서드 시그니처를 바꾸지 않기 위해(= `OrderSseApiDocs` 인터페이스 변경 불필요)
     * `HttpServletResponse`는 파라미터로 받지 않고 `RequestContextHolder`로 꺼낸다.
     */
    private fun writeAndAbort(ex: BusinessException): Nothing {
        logger.warn { "[BUSINESS_ERROR][SSE] code=${ex.errorCode.code}, message=${ex.message}" }

        // 정상적인 서블릿 요청 컨텍스트라면 발생하지 않아야 하는 방어적 분기.
        // 응답 객체를 못 얻으면 예외를 원래대로 흘려보내 GlobalExceptionHandler가 처리하게 둔다.
        val response =
            (RequestContextHolder.currentRequestAttributes() as? ServletRequestAttributes)?.response
                ?: throw ex

        response.status = ex.httpStatus.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        ex.retryAfter?.let { response.setHeader("Retry-After", it.seconds.toString()) }
        response.writer.use { it.write(objectMapper.writeValueAsString(ex.toErrorResponse())) }

        throw SseResponseAlreadyWrittenException()
    }

    /** [writeAndAbort]가 이미 응답을 완전히 써놓은 뒤 메서드 실행만 중단시키기 위한 내부 마커. */
    private class SseResponseAlreadyWrittenException : RuntimeException()

    /**
     * 컨트롤러 로컬 핸들러라 `@ControllerAdvice`(`GlobalExceptionHandler`)보다 항상 먼저
     * 매칭된다. 반환 타입이 `Unit`이라 `HttpMessageConverter` 협상 자체가 일어나지 않으며,
     * 응답은 [writeAndAbort]에서 이미 완료했으므로 여기서는 아무 것도 하지 않는다.
     */
    @ExceptionHandler(SseResponseAlreadyWrittenException::class)
    fun handleAlreadyWritten() = Unit

    companion object {
        private const val ORDER_RESOURCE = "order"
        private val logger = KotlinLogging.logger {}
    }
}
