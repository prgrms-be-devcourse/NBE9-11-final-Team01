package com.develop.snaptix.global.redis.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Redis 키 TTL 정책의 단일 진실 소스(SSOT).
 *
 * 모든 게이트웨이/AOP는 TTL을 코드에 하드코딩하지 않고 본 프로퍼티에서 주입받는다.
 * 봉투 일관성(멱등 인게스트 TTL ⊇ 워커 홀드 재앵커)을 한곳에서 관리하기 위함이다.
 *
 * 값은 [Duration] 포맷으로 바인딩된다(예: `8m`, `60s`, `1h`).
 *
 * @property ingestEnvelope 멱등/소유권 인게스트 봉투. 큐 대기 + 홀드 5분 + 여유(권장 8분, 부하 테스트로 조정).
 * @property orderHold ORDER_HOLD TTL. 워커가 멱등 키를 재앵커링하는 기준값.
 * @property webhookProcessed webhook:processed 멱등 가드 TTL.
 * @property paymentApprove payment:approve 이중 클릭 가드 TTL.
 * @property eventInfo event:info Cache-Aside TTL (Story 1.1 / 11.2 / 13.2 동일).
 * @property orderPending order:pending 처리 대기 보정 TTL.
 * @property rateLimitSecond rate_limit 초 단위 윈도우.
 * @property rateLimitMinute rate_limit 분 단위 윈도우.
 */
@ConfigurationProperties(prefix = "snaptix.redis.ttl")
data class RedisTtlProperties(
    val ingestEnvelope: Duration = Duration.ofMinutes(DEFAULT_INGEST_ENVELOPE_MINUTES),
    val orderHold: Duration = Duration.ofMinutes(DEFAULT_ORDER_HOLD_MINUTES),
    val webhookProcessed: Duration = Duration.ofMinutes(DEFAULT_WEBHOOK_PROCESSED_MINUTES),
    val paymentApprove: Duration = Duration.ofSeconds(DEFAULT_PAYMENT_APPROVE_SECONDS),
    val eventInfo: Duration = Duration.ofHours(DEFAULT_EVENT_INFO_HOURS),
    val orderPending: Duration = Duration.ofMinutes(DEFAULT_ORDER_PENDING_MINUTES),
    val rateLimitSecond: Duration = Duration.ofSeconds(DEFAULT_RATE_LIMIT_SECOND_WINDOW),
    val rateLimitMinute: Duration = Duration.ofSeconds(DEFAULT_RATE_LIMIT_MINUTE_WINDOW),
) {
    companion object {
        private const val DEFAULT_INGEST_ENVELOPE_MINUTES = 8L
        private const val DEFAULT_ORDER_HOLD_MINUTES = 5L
        private const val DEFAULT_WEBHOOK_PROCESSED_MINUTES = 10L
        private const val DEFAULT_PAYMENT_APPROVE_SECONDS = 60L
        private const val DEFAULT_EVENT_INFO_HOURS = 1L
        private const val DEFAULT_ORDER_PENDING_MINUTES = 2L
        private const val DEFAULT_RATE_LIMIT_SECOND_WINDOW = 1L
        private const val DEFAULT_RATE_LIMIT_MINUTE_WINDOW = 60L
    }
}
