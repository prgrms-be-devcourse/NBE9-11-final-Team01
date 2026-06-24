package com.develop.snaptix.domain.payment.controller

import com.develop.snaptix.domain.payment.dto.MockPaymentApproveRequest
import com.develop.snaptix.domain.payment.dto.MockPaymentApproveResponse
import com.develop.snaptix.domain.payment.service.MockPaymentApproveService
import com.develop.snaptix.global.exception.ErrorResponse
import com.develop.snaptix.global.security.auth.CurrentUserProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Mock Payments", description = "MVP용 모의 결제 API")
@RestController
@RequestMapping("/api/v1/payments/mock")
class MockPaymentController(
    private val mockPaymentApproveService: MockPaymentApproveService,
    private val currentUserProvider: CurrentUserProvider,
) {
    @Operation(
        summary = "모의 결제 승인 요청",
        description =
            "READY_TO_PAY 이벤트를 수신한 사용자가 결제를 요청합니다. " +
                "결제 결과 확정은 Webhook API를 통해 별도로 처리됩니다.",
        security = [SecurityRequirement(name = "accessToken")],
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "결제 요청 전송 성공",
                content = [Content(schema = Schema(implementation = MockPaymentApproveResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "orderId 형식 오류",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "주문 소유자 불일치",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "주문 없음",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "결제 가능 상태가 아니거나 결제 대기 시간 초과",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping("/approve")
    @PreAuthorize("hasRole('USER')")
    fun approve(
        @Valid
        @RequestBody
        request: MockPaymentApproveRequest,
    ): ResponseEntity<MockPaymentApproveResponse> {
        val response =
            mockPaymentApproveService.approve(
                userId = currentUserProvider.getCurrentUserId(),
                request = request,
            )
        return ResponseEntity.ok(response)
    }
}
