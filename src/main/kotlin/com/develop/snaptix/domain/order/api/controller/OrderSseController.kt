package com.develop.snaptix.domain.order.api.controller

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/v1/orders/sse")
class OrderSseController(
    private val sseConnectionManager: SseConnectionManager,
) {
    @GetMapping("/{orderId}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @PreAuthorize("hasRole('USER')")
    fun subscribe(
        @AuthenticationPrincipal(expression = "userId") userId: Long?,
        @PathVariable orderId: String,
    ): SseEmitter {
        val validUserId = userId ?: throw BusinessException(ErrorCode.TOKEN_MISSING)
        return sseConnectionManager.connect(
            key = SseChannelKey(ORDER_RESOURCE, orderId),
            userId = validUserId.toString(),
        )
    }

    companion object {
        private const val ORDER_RESOURCE = "order"
    }
}
