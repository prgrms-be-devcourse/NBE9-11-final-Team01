package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
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

    @Test
    fun `ACK 완료 메시지는 MINID trim으로 제거된다`() {
        val message = orderMessage()
        gateway.ensureGroup(message.eventId, GROUP)
        val messageId = gateway.add(message)
        gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)
        gateway.ack(message.eventId, GROUP, messageId)

        val result = gateway.trimAcknowledged(message.eventId, GROUP)

        assertThat(result.trimmedCount).isEqualTo(1L)
        assertThat(result.minId).isNotNull()
        assertThat(gateway.length(message.eventId)).isZero()
    }

    @Test
    fun `PEL에 남은 미확인 메시지는 MINID trim으로 제거되지 않는다`() {
        val message = orderMessage()
        gateway.ensureGroup(message.eventId, GROUP)
        val messageId = gateway.add(message)
        gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)

        val result = gateway.trimAcknowledged(message.eventId, GROUP)

        assertThat(result.trimmedCount).isZero()
        assertThat(result.minId).isEqualTo(messageId)
        assertThat(remainingRecordIds(message.eventId)).containsExactly(messageId)
    }

    @Test
    fun `ACK 완료 메시지와 PEL 메시지가 함께 있으면 ACK 완료 메시지만 제거된다`() {
        val eventId = UUID.randomUUID()
        val ackedMessageId = gateway.add(orderMessage(eventId))
        val pendingMessageId = gateway.add(orderMessage(eventId))
        gateway.ensureGroup(eventId, GROUP)
        gateway.read(eventId, GROUP, CONSUMER, READ_COUNT)
        gateway.ack(eventId, GROUP, ackedMessageId)

        val result = gateway.trimAcknowledged(eventId, GROUP)

        assertThat(result.trimmedCount).isEqualTo(1L)
        assertThat(result.minId).isEqualTo(pendingMessageId)
        assertThat(remainingRecordIds(eventId)).containsExactly(pendingMessageId)
    }

    @Test
    fun `Consumer Group이 없으면 MINID trim은 no-op으로 처리된다`() {
        val message = orderMessage()
        val messageId = gateway.add(message)

        val result = gateway.trimAcknowledged(message.eventId, GROUP)

        assertThat(result.trimmedCount).isZero()
        assertThat(result.minId).isNull()
        assertThat(remainingRecordIds(message.eventId)).containsExactly(messageId)
    }

    private fun remainingRecordIds(eventId: UUID): List<String> = redisTemplate
        .opsForStream<String, String>()
        .range("queue:order:$eventId", Range.unbounded())
        .orEmpty()
        .map { it.id.value }

    companion object {
        private const val READ_COUNT = 10
        private const val GROUP = "order-workers"
        private const val CONSUMER = "consumer-1"

        /** 각 테스트가 독립적인 스트림 키를 갖도록 eventId를 매번 새로 생성한다. */
        fun orderMessage(eventId: UUID = UUID.randomUUID()) = OrderMessage(
            orderId = UUID.randomUUID(),
            userId = 1L,
            eventId = eventId,
            zoneId = 10L,
        )
    }
}
