package com.develop.snaptix.global.realtime.port

import com.develop.snaptix.global.realtime.SseChannelKey

/**
 * 소유권 검증 포트. 도메인 지식이 필요한 부분이므로 각 도메인이 구현(어댑터)을 주입한다.
 * (주문 도메인 구현은 PR-07: OrderOwnershipChecker, domain/reservation 하위)
 *
 * `global.realtime` 패키지는 이 인터페이스만 알고, 구체 검증 규칙(reservations.user_id,
 * order:owner:{orderId} 등)은 알지 못한다.
 */
interface OwnershipChecker {
    fun check(
        key: SseChannelKey,
        userId: String,
    ): OwnershipResult
}

/**
 * 소유권 검증 결과. 구현(PR-02)에서 다음 ErrorCode로 매핑된다.
 *  - OWNED     : 구독 허용 (행 존재 시 소유자 일치, 또는 PENDING 단계 소유권 키 일치)
 *  - FORBIDDEN : 소유자 불일치 → BusinessException(ErrorCode.FORBIDDEN_ACCESS) → 403
 *  - NOT_FOUND : 대상도 소유권 키도 없음 → BusinessException(ErrorCode.NOT_FOUND) → 404
 */
enum class OwnershipResult {
    OWNED,
    FORBIDDEN,
    NOT_FOUND,
}
