package com.develop.snaptix.global.aop.annotation

/**
 * IdempotencyAspect가 멱등 키 구성에 필요한 값을 추출하기 위한 인터페이스.
 *
 * @Idempotent 메서드의 인자 중 이 인터페이스를 구현한 객체가 반드시 하나 존재해야 한다.
 *
 * 사용 예:
 *   data class OrderRequest(
 *       override val orderId: String,   // 컨트롤러에서 생성한 UUID
 *       override val eventId: String,   // events.public_id (UUID)
 *       val zoneId: Long,
 *   ) : IdempotencyTarget
 */
interface IdempotencyTarget {
    /** 컨트롤러/상위 레이어에서 생성한 주문 ID (UUID). compare-and-delete 토큰으로 사용. */
    val orderId: String

    /** events.public_id (UUID). DB 조회 없이 인게스트 경로에서 직접 사용 (DB-free). */
    val eventId: String
}
