package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest
class OrderStreamGatewayTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var gateway: OrderStreamGateway

    @Test
    fun `XADD 적재 후 XLEN이 1이다`() {
        val message = orderMessage()

        gateway.add(message)

        assertThat(gateway.length(message.eventId)).isEqualTo(1L)
    }

    @Test
    fun `그룹 생성 후 소비하면 적재한 메시지를 읽는다`() {
        val message = orderMessage()
        gateway.ensureGroup(message.eventId, GROUP)
        val messageId = gateway.add(message)

        val messages = gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)

        assertThat(messages).hasSize(1)
        assertThat(messages.first().id).isEqualTo(messageId)
        assertThat(messages.first().body).containsEntry(OrderMessage.FIELD_ORDER_ID, message.orderId.toString())
        assertThat(messages.first().body).containsEntry(OrderMessage.FIELD_EVENT_ID, message.eventId.toString())
    }

    @Test
    fun `ACK하면 확인 수 1을 반환하고 동일 컨슈머가 신규 소비 시 재배달되지 않는다`() {
        val message = orderMessage()
        gateway.ensureGroup(message.eventId, GROUP)
        val messageId = gateway.add(message)
        gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)

        val acked = gateway.ack(message.eventId, GROUP, messageId)

        assertThat(acked).isEqualTo(1L)
        assertThat(gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)).isEmpty()
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val READ_COUNT = 10
        private const val GROUP = "order-workers"
        private const val CONSUMER = "consumer-1"

        /** 각 테스트가 독립적인 스트림 키를 갖도록 eventId를 매번 새로 생성한다. */
        fun orderMessage() = OrderMessage(
            orderId = UUID.randomUUID(),
            userId = 1L,
            eventId = UUID.randomUUID(),
            zoneId = 10L,
        )
    }
}
