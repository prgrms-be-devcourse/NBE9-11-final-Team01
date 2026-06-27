package com.develop.snaptix.domain.event.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 이벤트 CLOSED 후 고아 Redis 키 정리 설정. (event:info/stock/claimed 라이프사이클 위생)
 * application.yaml `event.cleanup.*` 바인딩.
 *  - cron       : 고아 키 스윕 주기 (기본 매시 정각)
 *  - window     : 스윕 대상 = CLOSED 이고 updated_at >= now − window (기본 1일, case-a 다운타임 커버)
 *  - ttl        : CLOSED 정리 시 claimed/stock 백스톱 TTL 기준값 (기본 1시간 — 죽은 키라 짧게)
 *  - ttl-jitter : 백스톱 TTL 지터 비율(±), 동시 만료(snowstorm) 분산 (기본 0.1 = ±10%)
 */
@Component
@ConfigurationProperties(prefix = "event.cleanup")
class EventCleanupProperties {
    var cron: String = "0 0 * * * *"
    var window: Duration = Duration.ofDays(1)
    var ttl: Duration = Duration.ofHours(1)
    var ttlJitter: Double = 0.1
}
