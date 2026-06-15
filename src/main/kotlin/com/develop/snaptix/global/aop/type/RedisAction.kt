package com.develop.snaptix.global.aop.type

enum class RedisAction {
    LUASCRIPT_DECREASE, // 재고 원자 차감 Lua Script
    QUEUE_PUSH, // 주문 큐 적재
    HOLD_CREATE, // ORDER_HOLD 키 생성
    HOLD_RELEASE, // ORDER_HOLD 키 삭제 (결제 성공/실패)
    HOLD_EXPIRE, // ORDER_HOLD TTL 만료 감지
    IDEMPOTENCY_CHECK, // 멱등성 키 SET NX
    WEBHOOK_IDEMPOTENCY, // Webhook 중복 처리 방지 키
    RECONCILE_RUN, // 정합성 배치 수동/자동 실행
    CB_STATE_CHANGE, // 서킷 브레이커 상태 전환
    RATE_LIMIT_CHECK, // IP Rate Limiting 카운터
    CACHE_GET, // event:info 캐시 조회
    CACHE_SET, // event:info 캐시 저장
}
