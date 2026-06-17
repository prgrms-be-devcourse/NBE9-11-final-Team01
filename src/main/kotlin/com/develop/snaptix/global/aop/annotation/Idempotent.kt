package com.develop.snaptix.global.aop.annotation

/**
 * 동일 사용자 + 동일 이벤트의 중복 요청을 Redis SET NX로 원자 차단하는 AOP 어노테이션.
 *
 * 적용 조건:
 *   - 메서드 인자 중 [IdempotencyTarget]을 구현한 객체가 반드시 하나 있어야 한다.
 *   - orderId는 이 메서드 호출 전(컨트롤러)에서 생성되어 인자로 전달되어야 한다.
 *
 * 사용 예:
 *   @Idempotent
 *   fun enqueue(request: OrderRequest): OrderResponse { ... }
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Idempotent
