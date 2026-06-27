package com.develop.snaptix.domain.order.worker.port

import java.util.UUID

/**
 * 재고 보상 불변식 포트. (#6a에서 선언, 실제 구현은 #7 CompensationService)
 *
 * 호출 계약: orderId ∈ claimed **이고** 커밋된 reservation 행이 없을 때만 +1 + SREM.
 * 이중 보상 방지(idempotency)는 구현체([StockRedisGateway.compensate]) 책임.
 *
 * 호출 지점:
 *  - [OrderProcessingService] — DB INSERT 실패 시 try-finally 보상
 *  - #6b — `uk_active_user_event` 제약 위반 분기 보상
 *  - #7  — 구현체가 이 인터페이스를 대체
 */
fun interface CompensationPort {
    /**
     * Redis 재고+claimed 상태를 INSERT 이전으로 되돌린다.
     *
     * @param orderId 보상 대상 주문 ID
     * @param zoneId  재고를 복구할 구역 ID
     */
    fun compensateIfLeaked(
        orderId: UUID,
        zoneId: Long,
    )
}
