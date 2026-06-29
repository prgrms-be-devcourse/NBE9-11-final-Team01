package com.develop.snaptix.global.realtime

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * SSE 실시간 설정 (PR-03).
 *
 * 등록: 메인 클래스에 `@ConfigurationPropertiesScan` 또는
 *       `@EnableConfigurationProperties(RealtimeProperties::class)` 필요.
 *
 * application.yml 예:
 *   realtime:
 *     sse:
 *       timeout: 8m            # 총 수명(큐 대기 + 홀드 5분 + 여유). idle 아님.
 *       heartbeat-interval: 30s
 */
@ConfigurationProperties(prefix = "realtime.sse")
data class RealtimeProperties(
    /**
     * SSE 연결 타임아웃 봉투. **총 수명이며 idle 타임아웃이 아니다.**
     * 고부하 시 큐 대기를 덮도록 `최대 큐 대기 + 홀드 5분 + 여유`로 잡는다(권장 ≥ 8분, 결정 D2).
     */
    val timeout: Duration = Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES),
    /** heartbeat(주석 ping) 발송 주기. */
    val heartbeatInterval: Duration = Duration.ofSeconds(DEFAULT_HEARTBEAT_SECONDS),
) {
    init {
        require(timeout > Duration.ZERO) { "realtime.sse.timeout 은 양수여야 합니다: $timeout" }
        require(heartbeatInterval > Duration.ZERO) {
            "realtime.sse.heartbeat-interval 은 양수여야 합니다: $heartbeatInterval"
        }
        require(timeout > heartbeatInterval) {
            "realtime.sse.timeout($timeout) 은 heartbeat-interval($heartbeatInterval) 보다 커야 합니다"
        }
    }

    fun timeoutMillis(): Long = timeout.toMillis()

    companion object {
        /** 타임아웃 봉투 기본값(분). 큐 대기 + 홀드 5분 + 여유 (결정 D2) */
        private const val DEFAULT_TIMEOUT_MINUTES = 8L

        /** heartbeat 발송 주기 기본값(초) */
        private const val DEFAULT_HEARTBEAT_SECONDS = 30L
    }
}
