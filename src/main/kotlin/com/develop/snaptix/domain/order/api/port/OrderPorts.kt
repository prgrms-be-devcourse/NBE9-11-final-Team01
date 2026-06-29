package com.develop.snaptix.domain.order.api.port

import com.develop.snaptix.domain.order.api.dto.OrderAcceptedResponse
import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.domain.order.api.dto.OrderStatusResponse

fun interface OrderIngestPort {
    fun ingest(
        userId: Long,
        request: OrderRequest,
        ip: String,
    ): OrderAcceptedResponse
}

fun interface OrderQueryPort {
    fun getStatus(
        userId: Long,
        orderId: String,
    ): OrderStatusResponse
}
