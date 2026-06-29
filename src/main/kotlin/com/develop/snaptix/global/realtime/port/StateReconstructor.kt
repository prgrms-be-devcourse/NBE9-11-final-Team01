package com.develop.snaptix.global.realtime.port

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent

/**
 * 재연결/연결 시 DB(SSOT) 기준 현재 상태를 SSE 이벤트로 재구성하는 포트.
 * 각 도메인이 구현을 주입한다. (주문 도메인 구현은 PR-07: OrderStateReconstructor)
 *
 * Redis Pub/Sub은 비영속이라, SSE가 끊긴 사이 publish된 신호(예: READY_TO_PAY)는 소실된다.
 * connect 시 이 포트로 현재 상태를 재구성해 통지를 복구한다.
 *
 * 결정 D4: 주문 도메인 구현은 ORDER_HOLD 키가 아니라
 * `reservation.status = PENDING_PAYMENT` + created_at 홀드 윈도우로 판정한다.
 *
 * @return 재구성할 이벤트, 없으면 [null] (구독만 유지하고 대기)
 */
fun interface StateReconstructor {
    fun reconstruct(key: SseChannelKey): SseEvent?
}
