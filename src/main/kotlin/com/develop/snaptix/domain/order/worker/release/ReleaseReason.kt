package com.develop.snaptix.domain.order.worker.release

/**
 * 재고 복구가 필요한 실패 사유.
 *
 * [StockReleaseService.release]의 `reason` 파라미터로 사용되며
 * SSE 이벤트 타입 결정에도 사용된다.
 *
 * - [PAYMENT_TIMEOUT] : 홀드 만료 → `#10 HoldExpiryWorker` 호출 (`PAYMENT_TIMEOUT` SSE)
 * - [PAYMENT_FAILED]  : 결제 실패 웹훅 → `MockPaymentWebhookService` 호출 (`ORDER_FAILED` SSE)
 */
enum class ReleaseReason {
    PAYMENT_TIMEOUT,
    PAYMENT_FAILED,
}
