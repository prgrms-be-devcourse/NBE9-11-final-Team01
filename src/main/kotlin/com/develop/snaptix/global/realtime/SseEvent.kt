package com.develop.snaptix.global.realtime

/**
 * SSE로 전송할 페이로드.
 *
 * - [name]     : canonical 이벤트명. (READY_TO_PAY · ORDER_FAILED · TICKET_ISSUED ·
 *                PAYMENT_TIMEOUT · PAYMENT_FAILED)
 * - [data]     : 직렬화되어 클라이언트로 전송될 본문.
 * - [terminal] : 전송 후 연결을 종료(complete)할지 여부.
 *
 * 결정 D1 (작업 명세서 §13):
 *  - terminal = true  → 전송 후 complete(). (TICKET_ISSUED / ORDER_FAILED /
 *                       PAYMENT_TIMEOUT / PAYMENT_FAILED)
 *  - terminal = false → 연결을 유지한다. (READY_TO_PAY) 사용자가 동일 연결로
 *                       결제 결과까지 받기 위함.
 */
data class SseEvent(
    val name: String,
    val data: Any,
    val terminal: Boolean,
) {
    companion object {
        /** 연결 유지형 이벤트 (예: READY_TO_PAY) */
        fun ongoing(
            name: String,
            data: Any,
        ): SseEvent = SseEvent(name, data, terminal = false)

        /** 종료형 이벤트 (예: TICKET_ISSUED, ORDER_FAILED) */
        fun terminal(
            name: String,
            data: Any,
        ): SseEvent = SseEvent(name, data, terminal = true)
    }
}
