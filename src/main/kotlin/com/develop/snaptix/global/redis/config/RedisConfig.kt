package com.develop.snaptix.global.redis.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Redis 인프라 공통 설정.
 *
 * 직렬화 정책:
 * - `StringRedisTemplate`(키·값 모두 String 직렬화)은 Spring Boot가 자동 구성하므로
 *   별도 빈으로 재정의하지 않는다. 모든 게이트웨이는 String 기반으로 동작하고,
 *   값의 JSON 직렬화/역직렬화는 각 게이트웨이(예: EventCache)에서 수행한다.
 *   → 기존 키 값 포맷과의 호환을 유지하여 리팩터링 회귀 위험을 제거한다.
 *
 * 본 설정의 역할:
 * - [RedisTtlProperties]를 활성화하여 TTL 봉투 정책을 단일 소스로 제공한다.
 *
 * 참고: 만약 다른 위치에 RedisConfig가 이미 존재한다면, 본 클래스를 추가하는 대신
 * 기존 설정에 `@EnableConfigurationProperties(RedisTtlProperties::class)`를 병합한다.
 */
@Configuration
@EnableConfigurationProperties(RedisTtlProperties::class)
class RedisConfig
