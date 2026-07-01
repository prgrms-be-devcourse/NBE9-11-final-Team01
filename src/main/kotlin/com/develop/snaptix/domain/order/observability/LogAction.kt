package com.develop.snaptix.domain.order.observability

/**
 * 주문 도메인 구조화 로깅 AOP 어노테이션.
 *
 * [OrderLoggingAspect] 가 가로채어 다음을 자동 기록한다.
 *   - MDC: traceId / userId / eventId / zoneId
 *   - action (이 어노테이션 값 — RedisAction 상수 사용 권장)
 *   - result: SUCCESS | ERROR
 *   - executionTimeMs: 메서드 실행 시간
 *
 * 주의: Spring proxy 기반 AOP 이므로 **public 메서드, 외부 빈 호출** 에서만 동작한다.
 * private / self-invocation 에는 인라인 로깅을 사용한다.
 *
 * 사용 예:
 * ```kotlin
 * @LogAction("QUEUE_XADD")
 * override fun ingest(userId: Long, request: OrderRequest, ip: String): OrderAcceptedResponse { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogAction(
    /** RedisAction 열거형 name() 값 또는 임의 action 식별자 */
    val action: String,
)
