package com.develop.snaptix.domain.order.api.controller
import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse
import com.develop.snaptix.domain.order.api.port.OrderIngestPort
import com.develop.snaptix.domain.order.api.port.OrderQueryPort
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
        @AuthenticationPrincipal userId: Long,
        @Validated @RequestBody request: OrderRequest,
    ): ResponseEntity<OrderAcceptedResponse> {
        val response = orderIngestPort.ingest(userId, request)
        return ResponseEntity.accepted().body(response)
    }

    @GetMapping("/{orderId}")
    fun getOrderStatus(
        @AuthenticationPrincipal userId: Long,
        @PathVariable orderId: String,
    ): ResponseEntity<OrderStatusResponse> {
        val response = orderQueryPort.getStatus(userId, orderId)
        return ResponseEntity.ok(response)
    }
}
