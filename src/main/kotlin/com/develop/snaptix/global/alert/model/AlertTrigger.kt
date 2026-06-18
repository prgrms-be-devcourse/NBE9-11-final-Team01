package com.develop.snaptix.global.alert.model

enum class AlertTrigger(
    val severity: AlertSeverity,
    val summary: String,
) {
    CIRCUIT_OPEN(
        severity = AlertSeverity.CRITICAL,
        summary = "Redis 서킷 OPEN - 신규 주문 503 차단 중",
    ),
    REBUILD_STARTED(
        severity = AlertSeverity.INFO,
        summary = "Redis 상태 재구축 시작",
    ),
    REBUILD_COMPLETED(
        severity = AlertSeverity.INFO,
        summary = "Redis 상태 재구축 완료",
    ),
    REBUILD_FAILED(
        severity = AlertSeverity.CRITICAL,
        summary = "Redis 상태 재구축 실패 - 수동 개입 필요",
    ),
    STOCK_DRIFT_OVERSELL(
        severity = AlertSeverity.CRITICAL,
        summary = "오버셀 드리프트 감지",
    ),
    REPROCESS_SURGE(
        severity = AlertSeverity.WARN,
        summary = "워커 재처리율 급증",
    ),
    QUEUE_TRIM_LOSS(
        severity = AlertSeverity.CRITICAL,
        summary = "큐 트림 유실 위험 / 백로그 포화",
    ),
}
