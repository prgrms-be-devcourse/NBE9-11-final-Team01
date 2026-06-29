package com.develop.snaptix.domain.ticket.repository

/**
 * 티켓 코드 조회 포트 (PR-13 신규).
 *
 * `GET /orders/{orderId}` 폴링에서 CONFIRMED 상태 응답에 ticketCode를 동봉하기 위해
 * 도입한 최소 읽기 포트. 결제 팀의 tickets 테이블 산출물을 읽는다(Story 10.1-B).
 *
 * 계약: orderId(reservations.order_id)를 기준으로 발급된 ticket_code를 반환한다.
 *  - CONFIRMED 상태이고 티켓이 발급된 경우 → ticket_code(UUID 문자열)
 *  - 아직 발급 전이거나 조회 실패 시 → null (호출부에서 null 안전 처리)
 */
fun interface TicketQuery {
    fun findTicketCodeByOrderId(orderId: String): String?
}
