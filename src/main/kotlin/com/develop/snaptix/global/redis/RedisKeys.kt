package com.develop.snaptix.global.redis

/**
 * 재고/점유/이벤트 캐시 Redis 키 규약 단일 소스. (공통 규약)
 *
 *  - stock     : `ZONE:{zoneId}:stock`      ({zoneId}=내부 PK bigint)
 *  - claimed   : `ZONE:{zoneId}:claimed`    (차감 성공 orderId 집합)
 *  - eventInfo : `event:info:{publicId}`    ({publicId}=UUID)
 */
object RedisKeys {
    fun stock(zoneId: Long): String = "ZONE:$zoneId:stock"

    fun claimed(zoneId: Long): String = "ZONE:$zoneId:claimed"

    fun eventInfo(eventPublicId: String): String = "event:info:$eventPublicId"
}
