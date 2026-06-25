package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse
import com.develop.snaptix.domain.order.api.port.OrderIngestPort
import com.develop.snaptix.domain.order.api.port.OrderQueryPort
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
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
    @PreAuthorize("hasRole('USER')")
    @PostMapping
    fun createOrder(
        @AuthenticationPrincipal userId: Long?,
        @Validated @RequestBody request: OrderRequest,
    ): ResponseEntity<OrderAcceptedResponse> {
        val validUserId = userId ?: throw BusinessException(ErrorCode.TOKEN_MISSING)

        val response = orderIngestPort.ingest(validUserId, request)
        return ResponseEntity.accepted().body(response)
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/{orderId}")
    fun getOrderStatus(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable orderId: String,
    ): ResponseEntity<OrderStatusResponse> {
        val validUserId = userId ?: throw BusinessException(ErrorCode.TOKEN_MISSING)

        val response = orderQueryPort.getStatus(validUserId, orderId)
        return ResponseEntity.ok(response)
    }
}
