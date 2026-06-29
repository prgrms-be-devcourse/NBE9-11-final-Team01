package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.global.redis.gateway.OwnershipRedisGateway
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * PENDING(예약 행 생성 전) 단계의 주문 소유권 조회 포트.
 * `order:owner:{orderId}` 값(=userId)을 읽는다. (ERD Redis Key 명세, Story 2.1)
 *
 * `order:owner` 는 주문 인게스트(POST /orders) 시 기록되는 키이므로 reservation 도메인 소관.
 */
fun interface OrderOwnerStore {
    /** @return 소유자 userId, 키 없으면 null */
    fun findOwnerUserId(orderId: String): Long?
}

/**
 * Redis 기반 구현. (ISSUE-16)
 *
 * 변경 전: @RedisCircuitBreaker AOP + StringRedisTemplate 직접 호출
 * 변경 후: ResilientRedisExecutor.execute() — 서킷브레이커·로깅을 단일 진입점에서 처리.
 *
 * @RedisCircuitBreaker AOP는 self-invocation·비동기 워커에서 누락 위험이 있었으나
 * 프로그래밍 방식 executor는 호출 경로에 무관하게 일관되게 적용된다.
 *
 * RedisAction.OWNERSHIP 을 사용한다.
 *   (order:owner:{orderId} SET/GET/DEL 을 커버하는 기존 값, 신규 추가 불필요)
 */
@Repository
class RedisOrderOwnerStore(
    private val ownershipRedisGateway: OwnershipRedisGateway,
) : OrderOwnerStore {
    override fun findOwnerUserId(orderId: String): Long? = runCatching {
        val orderUuid = UUID.fromString(orderId)
        ownershipRedisGateway.ownerOf(orderUuid)
    }.getOrNull()
}
