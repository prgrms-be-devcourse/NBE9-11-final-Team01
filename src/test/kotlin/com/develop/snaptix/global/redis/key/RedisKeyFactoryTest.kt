package com.develop.snaptix.global.redis.key

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * RedisKeyFactory 키 패턴 검증. 출력이 Redis 키 명세서 v3.1과 1:1 일치하는지 확인한다.
 * (순수 문자열 생성이므로 Spring 컨텍스트/Redis 불필요)
 */
class RedisKeyFactoryTest {
    private val keys = RedisKeyFactory()

    private val orderId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val eventId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `내부 PK(Long) 기반 키 패턴이 명세서와 일치한다`() {
        assertThat(keys.stock(ZONE_ID)).isEqualTo("ZONE:42:stock")
        assertThat(keys.claimed(ZONE_ID)).isEqualTo("ZONE:42:claimed")
    }

    @Test
    fun `orderId(UUID) 기반 키 패턴이 명세서와 일치한다`() {
        assertThat(keys.orderHold(orderId)).isEqualTo("ORDER_HOLD:$orderId")
        assertThat(keys.sseOrder(orderId)).isEqualTo("sse:order:$orderId")
        assertThat(keys.webhookProcessed(orderId)).isEqualTo("webhook:processed:$orderId")
        assertThat(keys.paymentApprove(orderId)).isEqualTo("payment:approve:$orderId")
        assertThat(keys.orderOwner(orderId)).isEqualTo("order:owner:$orderId")
    }

    @Test
    fun `유저x이벤트 키 패턴이 명세서와 일치한다`() {
        assertThat(keys.idempotency(USER_ID, eventId)).isEqualTo("idempotency:order:7:$eventId")
        assertThat(keys.orderPending(USER_ID, eventId)).isEqualTo("order:pending:7:$eventId")
    }

    @Test
    fun `rate limit 초_분 윈도우 키 패턴이 명세서와 일치한다`() {
        assertThat(keys.rateLimitSecond(IP)).isEqualTo("rate_limit:203.0.113.10:sec")
        assertThat(keys.rateLimitMinute(IP)).isEqualTo("rate_limit:203.0.113.10:min")
    }

    @Test
    fun `public_id(UUID) 기반 키 패턴이 명세서와 일치한다`() {
        assertThat(keys.queueOrder(eventId)).isEqualTo("queue:order:$eventId")
        assertThat(keys.eventInfo(eventId)).isEqualTo("event:info:$eventId")
    }

    @Test
    fun `이벤트 단위 집계 재고 키는 제공하지 않는다`() {
        // stock 키는 항상 zoneId 기반이며 stock:{eventId} 형태가 아니다(Story 1.1).
        assertThat(keys.stock(ZONE_ID)).doesNotContain(eventId.toString())
    }

    companion object {
        private const val ZONE_ID = 42L
        private const val USER_ID = 7L
        private const val IP = "203.0.113.10"
    }
}
