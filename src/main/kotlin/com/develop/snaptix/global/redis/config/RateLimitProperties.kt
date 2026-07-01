package com.develop.snaptix.global.redis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 주문 인제스트 rate limit 정책의 단일 진실 소스(SSOT).
 *
 * IP 기준 한도와 사용자(userId) 기준 한도를 병행한다.
 *
 * 배경: 기존에는 `OrderIngestService.checkRateLimit`이 클라이언트 IP 하나만 기준으로
 * 한도를 걸었다. 같은 IP(NAT, 사내망, 부하테스트 러너 등) 뒤에서 서로 다른 여러 사용자가
 * 요청하면, 사용자 수와 무관하게 그 IP 전체가 하나의 한도를 공유하게 되어 정상 요청까지
 * 오탐 429(RATE_LIMIT_EXCEEDED)로 막히는 문제가 있었다(k6 부하 테스트에서 backpressure_hit
 * 99%대로 재현).
 *
 *  - [userPerSecond] / [userPerMinute]: 인증된 사용자 본인 기준 남용 방지. 기존 IP 한도값
 *    (5/20)을 그대로 승계해 "1인당" 실질 한도는 회귀 없이 유지한다.
 *  - [ipPerSecond] / [ipPerMinute]: 비인증 단계까지 포함하는 최소 봇/DDoS 방어선. 사용자
 *    한도보다 넉넉하게 잡아 같은 IP에서 다수의 서로 다른 정상 사용자가 몰려도 쉽게 걸리지
 *    않도록 한다.
 *
 * 아래 기본값은 초기 제안값이며, 실제 트래픽 패턴에 맞춰 팀 정책으로 조정이 필요하다.
 */
@ConfigurationProperties(prefix = "snaptix.rate-limit")
data class RateLimitProperties(
    val userPerSecond: Int = DEFAULT_USER_PER_SECOND,
    val userPerMinute: Int = DEFAULT_USER_PER_MINUTE,
    val ipPerSecond: Int = DEFAULT_IP_PER_SECOND,
    val ipPerMinute: Int = DEFAULT_IP_PER_MINUTE,
) {
    companion object {
        private const val DEFAULT_USER_PER_SECOND = 5
        private const val DEFAULT_USER_PER_MINUTE = 20
        private const val DEFAULT_IP_PER_SECOND = 30
        private const val DEFAULT_IP_PER_MINUTE = 300
    }
}
