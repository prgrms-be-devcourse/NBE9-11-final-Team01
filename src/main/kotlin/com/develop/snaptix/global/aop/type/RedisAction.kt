package com.develop.snaptix.global.aop.type

/**
 * Redis 연산 유형 열거형 — v3.1 기준 정렬.
 *
 * 변경 이력 (이전 버전 → v3.1):
 *   - QUEUE_PUSH → QUEUE_XADD (Stream 전환)
 *   - XREADGROUP / XACK / MINID_TRIM / CLAIM_REPROCESS 추가 (Stream Consumer Group)
 *   - INGEST_BACKPRESSURE / COMPENSATE_STOCK / STOCK_DRIFT_FIX 추가 (v3.1 신규)
 *   - CACHE_INVALIDATE 추가 (event:info 무효화 추적용)
 *   - OWNERSHIP 추가 (order:owner PENDING 단계 소유권 — ISSUE-08)
 *   - PAYMENT_APPROVE 추가 (payment:approve 이중 클릭 가드 — ISSUE-09)
 *   - STOCK_GET / STOCK_REBUILD 추가 (재고 조회·재구축 — ISSUE-05-EXT)
 *   - STREAM_DEPTH_CHECK 추가 (XLEN/XPENDING 깊이 게이지 갱신 — #312)
 */
enum class RedisAction {
    // ── 주문 큐 (Stream) ──────────────────────────────────────────────

    /** XADD queue:order:{eventId} — 주문 Stream 적재 */
    QUEUE_XADD,

    /** XREADGROUP — Consumer Group 소비 */
    XREADGROUP,

    /** XACK — 메시지 처리 완료 확인 */
    XACK,

    /** MINID 트리밍 — ACK 완료분만 메모리 회수 */
    MINID_TRIM,

    /** XAUTOCLAIM 회수 후 재처리 */
    CLAIM_REPROCESS,

    /** XLEN ≥ 정원+α 시 429 백프레셔 */
    INGEST_BACKPRESSURE,

    /** XLEN·XPENDING 현재 깊이 게이지 갱신 (트림 스케줄러 주기마다) */
    STREAM_DEPTH_CHECK,

    // ── 재고 ─────────────────────────────────────────────────────────

    /** Lua Script 원자 차감 (ZONE:{zoneId}:stock) */
    LUASCRIPT_DECREASE,

    /** 재고 보상 +1 (SOLD_OUT·중복·DB롤백 터미널 경로 공통) */
    COMPENSATE_STOCK,

    /** 드리프트 누수 보정 SET (stock만, claimed 미접촉) */
    STOCK_DRIFT_FIX,

    /** 재고 조회 GET (드리프트 점검용) */
    STOCK_GET,

    /** 상태 재구축 — stock SET + claimed 원자 덮어쓰기 (Story 13.2) */
    STOCK_REBUILD,

    // ── ORDER_HOLD ────────────────────────────────────────────────────

    /** ORDER_HOLD:{orderId} 생성 */
    HOLD_CREATE,

    /** ORDER_HOLD:{orderId} 삭제 (결제 성공/실패) */
    HOLD_RELEASE,

    /** ORDER_HOLD TTL 만료 감지 */
    HOLD_EXPIRE,

    // ── 멱등성 ────────────────────────────────────────────────────────

    /** idempotency:order:{userId}:{eventId} SET NX */
    IDEMPOTENCY_CHECK,

    /** webhook:processed:{orderId} SET NX */
    WEBHOOK_IDEMPOTENCY,

    // ── 결제 가드 ─────────────────────────────────────────────────────

    /** payment:approve:{orderId} SET NX — 결제 승인 이중 클릭 차단 */
    PAYMENT_APPROVE,

    // ── 소유권 ────────────────────────────────────────────────────────

    /** order:owner:{orderId} SET/GET/DEL — PENDING 단계 주문 소유권 */
    OWNERSHIP,

    // ── 정합성 배치 ───────────────────────────────────────────────────

    /** PENDING_PAYMENT 타임아웃 정산 배치 실행 */
    RECONCILE_RUN,

    // ── 서킷 브레이커 ─────────────────────────────────────────────────

    /** CLOSED → OPEN → HALF_OPEN 상태 전환 */
    CB_STATE_CHANGE,

    /** 재구축 단일 실행 락 (SET NX / compare-and-delete) */
    REBUILD_LOCK,

    // ── Rate Limit ────────────────────────────────────────────────────

    /** INCR + EXPIRE IP 카운터 */
    RATE_LIMIT_CHECK,

    // ── Cache-Aside (event:info) ──────────────────────────────────────

    /** event:info:{publicId} GET */
    CACHE_GET,

    /** event:info:{publicId} SET */
    CACHE_SET,

    /** event:info:{publicId} DEL (상태변경·CLOSED 전환 시 무효화) */
    CACHE_INVALIDATE,
}
