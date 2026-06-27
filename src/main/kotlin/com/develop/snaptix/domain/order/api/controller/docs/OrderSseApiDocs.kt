package com.develop.snaptix.domain.order.api.controller.docs

import com.develop.snaptix.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

private const val ORDER_SSE_STREAM_EXAMPLE = """
: connected

event:READY_TO_PAY
data:{"type":"READY_TO_PAY","orderId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","status":"PENDING_PAYMENT","message":"좌석이 확보되었습니다. 결제 대기 시간 내에 결제를 완료해주세요.","paymentDeadline":"2026-06-27T10:05:00Z"}

event:TICKET_ISSUED
data:{"orderId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","status":"CONFIRMED"}

event:ORDER_FAILED
data:{"orderId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","status":"CANCELLED"}

event:PAYMENT_TIMEOUT
data:{"orderId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","status":"RELEASED"}

: ping
"""

private const val UNAUTHORIZED_EXAMPLE = """
{
  "code": "AUTH-006",
  "message": "인증이 필요합니다.",
  "errors": null
}
"""

private const val ACCESS_DENIED_EXAMPLE = """
{
  "code": "AUTH-005",
  "message": "접근 권한이 없습니다.",
  "errors": null
}
"""

private const val ORDER_ACCESS_DENIED_EXAMPLE = """
{
  "code": "AUTH-007",
  "message": "해당 리소스에 접근할 권한이 없습니다.",
  "errors": null
}
"""

private const val ORDER_NOT_FOUND_EXAMPLE = """
{
  "code": "COMMON-003",
  "message": "요청한 리소스를 찾을 수 없습니다.",
  "errors": null
}
"""

@Tag(name = "Order SSE", description = "주문 처리 상태 실시간 구독 API")
interface OrderSseApiDocs {
    @Operation(
        summary = "주문 처리 상태 SSE 구독",
        description =
            "사용자가 주문 처리 상태를 SSE로 구독합니다. accessToken 쿠키 기반 USER 인증이 필요하며, " +
                "응답은 application/json이 아닌 text/event-stream입니다. 연결 직후 서버는 현재 주문 상태를 재구성하여 " +
                "READY_TO_PAY, TICKET_ISSUED, ORDER_FAILED, PAYMENT_TIMEOUT 이벤트를 즉시 보낼 수 있습니다.",
        security = [SecurityRequirement(name = "accessToken")],
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "SSE 연결 성공",
                content = [
                    Content(
                        mediaType = "text/event-stream",
                        schema = Schema(type = "string"),
                        examples = [
                            ExampleObject(
                                name = "OrderStatusSseStream",
                                value = ORDER_SSE_STREAM_EXAMPLE,
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_EXAMPLE)],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "USER 권한 없음 또는 주문 소유자 불일치",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(name = "AccessDenied", value = ACCESS_DENIED_EXAMPLE),
                            ExampleObject(name = "OrderAccessDenied", value = ORDER_ACCESS_DENIED_EXAMPLE),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "구독 대상 주문을 찾을 수 없음",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "OrderNotFound", value = ORDER_NOT_FOUND_EXAMPLE)],
                    ),
                ],
            ),
        ],
    )
    fun subscribe(
        @Parameter(hidden = true)
        userId: Long?,
        @Parameter(
            description = "구독할 주문 외부 식별자(reservations.order_id)",
            example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        )
        orderId: String,
    ): SseEmitter
}
