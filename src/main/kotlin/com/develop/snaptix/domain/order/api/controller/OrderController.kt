package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse
import com.develop.snaptix.domain.order.api.port.OrderIngestPort
import com.develop.snaptix.domain.order.api.port.OrderQueryPort
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderIngestPort: OrderIngestPort,
    private val orderQueryPort: OrderQueryPort,
) {
    @PostMapping
    fun createOrder(
        @AuthenticationPrincipal(expression = "#this") userId: Long?,
        @Validated @RequestBody request: OrderRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<OrderAcceptedResponse> {
        val validUserId = userId ?: throw BusinessException(ErrorCode.TOKEN_MISSING)

        val ip =
            httpRequest
                .getHeader("X-Forwarded-For")
                ?.split(",")
                ?.first()
                ?.trim()
                ?: httpRequest.remoteAddr

        val response = orderIngestPort.ingest(validUserId, request, ip)
        return ResponseEntity.accepted().body(response)
    }

    @GetMapping("/{orderId}")
    fun getOrderStatus(
        @AuthenticationPrincipal(expression = "#this") userId: Long?,
        @PathVariable orderId: String,
    ): ResponseEntity<OrderStatusResponse> {
        val validUserId = userId ?: throw BusinessException(ErrorCode.TOKEN_MISSING)

        val response = orderQueryPort.getStatus(validUserId, orderId)
        return ResponseEntity.ok(response)
    }
}
