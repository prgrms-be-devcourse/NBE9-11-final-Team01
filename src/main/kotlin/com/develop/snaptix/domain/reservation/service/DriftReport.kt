package com.develop.snaptix.domain.reservation.service

/**
 * 드리프트 정산 1회 실행 결과. (작업 명세서 v2.1 §6)
 *
 * 스케줄러 로깅·메트릭, 테스트 단언용. 스펙상 외부 반환 계약은 없으나(Admin API 없음)
 * 관측성과 zone 단위 격리의 `failed` 가시화를 위해 반환한다.
 */
data class DriftReport(
    val fixed: Int, // 누수 보정(correctStock 적용)
    val oversell: Int, // 오버셀 알림 발송
    val unchanged: Int, // actual == expected
    val skipped: Int, // stock 키 부재 → skip
    val failed: Int, // zone/청크 단위 예외 격리
) {
    fun asLogPayload(): Map<String, Any> = mapOf(
        "fixed" to fixed,
        "oversell" to oversell,
        "unchanged" to unchanged,
        "skipped" to skipped,
        "failed" to failed,
    )

    /** 청크/zone 순회 중 카운트를 누적하는 가변 집계기. */
    class Accumulator {
        var fixed = 0
        var oversell = 0
        var unchanged = 0
        var skipped = 0
        var failed = 0

        fun toReport() = DriftReport(
            fixed = fixed,
            oversell = oversell,
            unchanged = unchanged,
            skipped = skipped,
            failed = failed,
        )
    }
}
