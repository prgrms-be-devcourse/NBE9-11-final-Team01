package com.develop.snaptix.domain.order.api.dto

import java.util.UUID

/**
 * Redis Stream(XADD)에 적재되는 주문 메세지의 강타입 값 객체.
 *
 * [배경]
 * 기존 Map<String, String> 방식은 컴파일 타임에 오타·타입 불일치를 잡을 수 없어
 * 워커가 메세지를 소비해 재고를 차감할 때 데이터 무결성이 깨질 위험(Stringly Typed 안티 패턴)이 있다.
 * 이 클래스가 인게스트 서비스와 워커 간의 명시적인 데이터 계약(Contract)을 수립한다.
 *
 * [사용처]
 * - 쓰기: OrderIngestService → OrderStreamGateway.add(message)
 * - 읽기: OrderWorker → OrderMessage.fromStreamPayload(map)
 */
data class OrderMessage(
    val orderId: UUID,
    val userId: Long,
    val eventId: UUID,
    val zoneId: Long,
) {
    /**
     * Redis Stream이 요구하는 Map<String, String> 포맷으로 변환한다.
     * 직렬화 책임이 이 클래스에 캡슐화되어 외부 오타를 원천 차단한다.
     */
    fun toStreamPayload(): Map<String, String> = mapOf(
        FIELD_ORDER_ID to orderId.toString(),
        FIELD_USER_ID to userId.toString(),
        FIELD_EVENT_ID to eventId.toString(),
        FIELD_ZONE_ID to zoneId.toString(),
    )

    companion object {
        const val FIELD_ORDER_ID = "orderId"
        const val FIELD_USER_ID = "userId"
        const val FIELD_EVENT_ID = "eventId"
        const val FIELD_ZONE_ID = "zoneId"

        /**
         * Redis Stream에서 읽은 Map<String, String>을 OrderMessage 로 역직렬화한다.
         * 필드 누락 시 IllegalArgumentException 을 던져 워커가 즉시 감지할 수 있게 한다.
         */
        fun fromStreamPayload(map: Map<String, String>): OrderMessage {
            fun require(key: String): String = map[key] ?: throw IllegalArgumentException("OrderMessage 필드 누락: $key")

            return OrderMessage(
                orderId = UUID.fromString(require(FIELD_ORDER_ID)),
                userId = require(FIELD_USER_ID).toLong(),
                eventId = UUID.fromString(require(FIELD_EVENT_ID)),
                zoneId = require(FIELD_ZONE_ID).toLong(),
            )
        }
    }
}
