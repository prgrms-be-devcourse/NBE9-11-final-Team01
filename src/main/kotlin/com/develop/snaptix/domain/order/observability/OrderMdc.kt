package com.develop.snaptix.domain.order.observability

import org.slf4j.MDC
import java.util.UUID

/**
 * 주문 도메인 MDC 키 상수 및 설정/해제 헬퍼.
 *
 * 구조화 로그 필드(traceId/userId/eventId/zoneId)를 MDC 에 심어
 * [OrderLoggingAspect] 및 logback JSON 인코더가 자동으로 수집하도록 한다.
 *
 * ## 사용 패턴
 * ```kotlin
 * OrderMdc.set(userId = userId, eventId = eventId, zoneId = zoneId)
 * try {
 *     // ... 비즈니스 로직
 * } finally {
 *     OrderMdc.clearOrderContext()
 * }
 * ```
 */
object OrderMdc {
    const val TRACE_ID = "traceId" // 전역 필터가 이미 주입
    const val USER_ID = "userId"
    const val EVENT_ID = "eventId"
    const val ZONE_ID = "zoneId"

    fun set(
        userId: Long? = null,
        eventId: UUID? = null,
        zoneId: Long? = null,
    ) {
        userId?.let { MDC.put(USER_ID, it.toString()) }
        eventId?.let { MDC.put(EVENT_ID, it.toString()) }
        zoneId?.let { MDC.put(ZONE_ID, it.toString()) }
    }

    /** 요청 단위로 주입한 Order 컨텍스트만 정리. traceId 는 건드리지 않는다. */
    fun clearOrderContext() {
        MDC.remove(USER_ID)
        MDC.remove(EVENT_ID)
        MDC.remove(ZONE_ID)
    }
}
