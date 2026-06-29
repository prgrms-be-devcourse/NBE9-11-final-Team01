package com.develop.snaptix.domain.event.service

/**
 * 고아 키 스윕 1회 결과. (Reconcile/Drift Report 패턴)
 *  - cleaned : 실제로 키를 삭제한 이벤트 수(deleted > 0)
 *  - skipped : 정리할 키가 없던 이벤트 수(deleted == 0, 이미 정리됨/멱등)
 *  - failed  : 이벤트 단위 예외 격리로 집계된 실패 수
 */
data class CleanupReport(
    val cleaned: Int,
    val skipped: Int,
    val failed: Int,
) {
    fun asLogPayload(): Map<String, Any> = mapOf(
        "cleaned" to cleaned,
        "skipped" to skipped,
        "failed" to failed,
    )

    class Accumulator {
        var cleaned = 0
        var skipped = 0
        var failed = 0

        fun toReport() = CleanupReport(cleaned = cleaned, skipped = skipped, failed = failed)
    }
}
