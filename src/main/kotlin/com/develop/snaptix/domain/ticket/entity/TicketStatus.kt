package com.develop.snaptix.domain.ticket.entity

/**
 * 티켓 상태.
 *
 * - [ISSUED] : 결제 확정 직후 발권된 상태. 입장 전.
 * - [USED]   : 현장 QR 검표 완료. 입장 처리 후.
 */
enum class TicketStatus {
    ISSUED,
    USED,
}
