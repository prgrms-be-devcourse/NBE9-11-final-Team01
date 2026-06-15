package global.exception

import global.exception.redis.RateLimitExceededException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class GlobalExceptionHandler {
    private val logger = KotlinLogging.logger {}

    // ✅ 비즈니스 예외 처리 (최우선)
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        ex: BusinessException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.warn { "[BUSINESS_ERROR] code=${ex.errorCode.code}, message=${ex.message}, path=${request.requestURI}" }
        return ResponseEntity.status(ex.httpStatus).body(ex.toErrorResponse())
    }

    // ✅ @Valid 검증 실패 (MethodArgumentNotValidException)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors =
            ex.bindingResult.fieldErrors.map { err ->
                ErrorResponse.FieldError(
                    field = err.field,
                    reason = err.defaultMessage ?: "유효성 검사 오류",
                )
            }

        val detail = fieldErrors.joinToString(", ") { "${it.field}: ${it.reason}" }
        logger.warn { "[VALIDATION_ERROR] detail=$detail, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorCode.VALIDATION_FAILED.toErrorResponse(fieldErrors))
    }

    // ✅ @Validated 메서드 파라미터 검증 실패 (HandlerMethodValidationException)
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidationException(
        ex: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors =
            ex.parameterValidationResults.flatMap { result ->
                val paramName = result.methodParameter.parameterName ?: "unknown"
                result.resolvableErrors.map { error ->
                    ErrorResponse.FieldError(
                        field = paramName,
                        reason = error.defaultMessage ?: "유효성 검사 오류",
                    )
                }
            }

        val detail = fieldErrors.joinToString(", ") { "${it.field}: ${it.reason}" }
        logger.warn { "[PARAM_VALIDATION_ERROR] detail=$detail, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorCode.VALIDATION_FAILED.toErrorResponse(fieldErrors))
    }

    // ✅ 404 Not Found (존재하지 않는 경로)
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(
        ex: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.warn { "[NOT_FOUND] method=${ex.httpMethod}, path=${request.requestURI}" }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorCode.NOT_FOUND.toErrorResponse())
    }

    // ✅ 타입 변환 실패 (MethodArgumentTypeMismatchException)
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val expectedType = ex.requiredType?.simpleName ?: "unknown"
        val detail = "파라미터 '${ex.name}'의 타입 변환에 실패했습니다. 기대 타입: $expectedType"
        logger.warn { "[TYPE_MISMATCH] param=${ex.name}, value=${ex.value}, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorCode.TYPE_MISMATCH.toErrorResponse(detail))
    }

    // ✅ 필수 파라미터 누락 (MissingServletRequestParameterException)
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(
        ex: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val detail = "필수 파라미터 '${ex.parameterName}'이(가) 누락되었습니다."
        logger.warn { "[PARAM_MISSING] param=${ex.parameterName}, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorCode.PARAM_MISSING.toErrorResponse(detail))
    }

    // ✅ HTTP 메서드 허용 안됨 (405)
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(
        ex: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val supported = ex.supportedMethods?.joinToString(", ") ?: "none"
        val detail = "지원하지 않는 HTTP 메서드 '${ex.method}'입니다. 지원: $supported"
        logger.warn { "[METHOD_NOT_ALLOWED] method=${ex.method}, supported=$supported, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ErrorCode.METHOD_NOT_ALLOWED.toErrorResponse(detail))
    }

    // ✅ Content-Type 지원 안됨 (415)
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(
        ex: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.warn { "[UNSUPPORTED_MEDIA_TYPE] contentType=${ex.contentType}, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ErrorCode.UNSUPPORTED_MEDIA_TYPE.toErrorResponse())
    }

    // ✅ 요청 본문 파싱 실패 (날짜 형식 오류 등)
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.warn { "[MESSAGE_NOT_READABLE] cause=${ex.mostSpecificCause?.message}, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorCode.INVALID_REQUEST_PARAMETER.toErrorResponse("요청 본문을 읽을 수 없습니다. 날짜/시간 형식을 확인해주세요."))
    }

    // ✅ DB 무결성 위반 (DataIntegrityViolationException)
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(
        ex: DataIntegrityViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        // ⚠️ 내부 예외 메시지 노출 금지
        logger.warn { "[DB_INTEGRITY] cause=${ex.mostSpecificCause.message}, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorCode.DUPLICATE_RESOURCE.toErrorResponse())
    }

    // ✅ 동일 ip에 대한 중복 요청 차단 (RateLimitExceededException)
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimit(
        ex: RateLimitExceededException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.warn { "[RATE_LIMIT] retryAfter=${ex.retryAfterSeconds}s, path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(ex.toErrorResponse())
    }

    // ✅ Fallback: 예상치 못한 모든 예외 (보안 고려 — 스택트레이스 노출 금지)
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "[INTERNAL_ERROR] path=${request.requestURI}" }

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorCode.INTERNAL_SERVER_ERROR.toErrorResponse())
    }
}
