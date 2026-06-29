package com.develop.snaptix.domain.order.api.dto

import java.time.Instant

data class OrderAcceptedResponse(
    val orderId: String,
    val sseUrl: String,
    val statusUrl: String,
    val message: String,
)

enum class OrderStatus {
    PENDING,
    READY_TO_PAY,
    CONFIRMED,
    FAILED,
    EXPIRED,
}

data class OrderStatusResponse(
    val orderId: String,
    val status: OrderStatus,
    val message: String? = null,
    val paymentDeadline: Instant? = null,
    val ticketCode: String? = null,
)
