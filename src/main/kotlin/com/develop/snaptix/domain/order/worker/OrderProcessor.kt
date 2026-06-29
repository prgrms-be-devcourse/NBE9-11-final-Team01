package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage

interface OrderProcessor {
    /**
     * 주문 메시지를 처리한다. (차감, 1인 1매 검사, 영속화 등)
     * 비터미널(일시적) 예외 발생 시 예외를 던져 XACK를 방지하고 PEL에 남겨야 한다.
     */
    fun process(message: OrderMessage)
}
