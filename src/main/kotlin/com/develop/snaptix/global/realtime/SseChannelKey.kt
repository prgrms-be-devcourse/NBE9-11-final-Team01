package com.develop.snaptix.global.realtime

/**
 * SSE 채널을 식별하는 도메인 무관 값 객체.
 *
 * - [resource] : 도메인 식별자 (예: "order", "payment", "delivery")
 * - [id]       : 외부 노출 식별자 (UUID, 예: orderId)
 *
 * 채널 키를 `sse:{resource}:{id}`로 일반화하여, 다른 도메인이 동일한 연결 관리 로직을
 * 재사용할 수 있게 한다. (작업 명세서 §3, 결정 D3)
 */
data class SseChannelKey(
    val resource: String,
    val id: String,
) {
    init {
        require(resource.isNotBlank()) { "resource must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
    }

    /** Redis Pub/Sub 채널명. 예: `sse:order:{orderId}` */
    fun redisChannel(): String = "sse:$resource:$id"

    /** 인메모리 레지스트리 키. 예: `order:{orderId}` */
    fun registryKey(): String = "$resource:$id"
}
