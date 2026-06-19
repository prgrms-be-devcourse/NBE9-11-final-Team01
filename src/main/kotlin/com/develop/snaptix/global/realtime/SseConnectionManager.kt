package com.develop.snaptix.global.realtime

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 도메인 무관 SSE 연결 관리 계약.
 *
 * 구현체:
 *  - InMemorySseConnectionManager (PR-02, 실제)
 *  - MockSseConnectionManager     (PR-09, 목)
 *
 * 두 구현은 동일한 계약 테스트(PR-10)를 통과해야 하며, DI 설정만으로 교체 가능하다.
 * 이 인터페이스는 PR-01에서 동결(freeze)되며, 이후 변경은 구현 서브 명세서 §6 절차를 따른다.
 */
interface SseConnectionManager {
    /**
     * SSE 연결을 생성하고 레지스트리에 등록한다.
     *
     * 처리:
     *  1. [OwnershipChecker]로 소유권 검증
     *     - FORBIDDEN  → BusinessException(ErrorCode.FORBIDDEN_ACCESS) → 403
     *     - NOT_FOUND  → BusinessException(ErrorCode.NOT_FOUND)        → 404
     *     - OWNED      → 연결 수립
     *     (예외는 전역 GlobalExceptionHandler가 ErrorResponse로 변환)
     *  2. 같은 키 재연결 시 기존 Emitter를 complete 후 교체 (활성 연결 1개 유지)
     *  3. 연결 직후 [StateReconstructor]로 현재 상태를 재구성하여, 값이 있으면 1회 전송
     *     (Pub/Sub 비영속 대비 — 끊긴 사이 publish된 신호 복구)
     *
     * @throws com.develop.snaptix.global.exception.BusinessException
     *         소유자 불일치(FORBIDDEN_ACCESS) 또는 대상 부재(NOT_FOUND)
     */
    fun connect(
        key: SseChannelKey,
        userId: String,
    ): SseEmitter

    /**
     * 지정 채널의 활성 Emitter로 이벤트를 비동기 전송한다.
     * 이 인스턴스에 해당 연결이 없으면 no-op (다른 인스턴스가 처리).
     * [SseEvent.terminal] 이 true면 전송 후 complete + 정리한다.
     */
    fun dispatch(
        key: SseChannelKey,
        event: SseEvent,
    )

    /** 연결을 명시적으로 종료(complete)하고 정리한다. */
    fun close(key: SseChannelKey)

    /** 현재 인스턴스의 활성 연결 수 (관측용). */
    fun activeConnections(): Int
}
