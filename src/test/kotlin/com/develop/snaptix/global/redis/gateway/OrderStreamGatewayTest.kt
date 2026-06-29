package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
import java.util.UUID

@SpringBootTest
class OrderStreamGatewayTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var gateway: OrderStreamGateway

    private val keys = RedisKeyFactory()

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

    @Test
    fun `Stream이 없으면 MINID trim은 no-op으로 처리된다`() {
        val eventId = UUID.randomUUID()

        val result = gateway.trimAcknowledged(eventId, GROUP)

        assertThat(result.trimmedCount).isZero()
        assertThat(result.minId).isNull()
        assertThat(gateway.length(eventId)).isZero()
    }

    @Test
    fun `전달된 메시지가 없으면 MINID trim은 no-op으로 처리된다`() {
        val eventId = UUID.randomUUID()
        gateway.ensureGroup(eventId, GROUP)

        val result = gateway.trimAcknowledged(eventId, GROUP)

        assertThat(result.trimmedCount).isZero()
        assertThat(result.minId).isNull()
        assertThat(gateway.length(eventId)).isZero()
    }

    @Test
    fun `idle 시간이 지난 PEL 메시지는 XAUTOCLAIM으로 회수된다`() {
        // given
        val message = orderMessage()
        gateway.ensureGroup(message.eventId, GROUP)
        val messageId = gateway.add(message)

        // consumer-1이 읽어서 PEL에 적재 (미확인 상태)
        gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)

        // 메시지가 min-idle-time을 확실히 초과하도록 넉넉히 대기
        Thread.sleep(50)

        // when: consumer-2가 1ms 이상 대기된 메시지를 회수 시도
        val result =
            gateway.claim(
                eventPublicId = message.eventId,
                group = GROUP,
                consumer = "consumer-2",
                minIdleTime = java.time.Duration.ofMillis(1),
            )

        // then
        assertThat(result.claimedMessages).hasSize(1)
        assertThat(result.claimedMessages.first().id).isEqualTo(messageId)
        assertThat(result.deletedIds).isEmpty()
        assertThat(result.nextStartId).isEqualTo("0-0")
    }

    @Test
    fun `idle 시간이 지나지 않은 PEL 메시지는 다른 워커가 가로채지 못한다`() {
        // given
        val message = orderMessage()
        gateway.ensureGroup(message.eventId, GROUP)
        gateway.add(message)

        // consumer-1이 읽어서 PEL에 적재
        gateway.read(message.eventId, GROUP, CONSUMER, READ_COUNT)

        // when: consumer-2가 매우 긴 idle 시간(1시간) 조건으로 회수 시도
        val result =
            gateway.claim(
                eventPublicId = message.eventId,
                group = GROUP,
                consumer = "consumer-2",
                minIdleTime = java.time.Duration.ofHours(1),
            )

        // then
        assertThat(result.claimedMessages).isEmpty()
        assertThat(result.deletedIds).isEmpty()
        assertThat(result.nextStartId).isEqualTo("0-0")
    }

    @Test
    fun `PEL에 등록되었으나 Stream에서 삭제된 메시지는 deletedIds로 반환된다`() {
        // given
        val message = orderMessage()
        val eventId = message.eventId
        gateway.ensureGroup(eventId, GROUP)
        val messageId = gateway.add(message)

        // consumer-1이 읽어서 PEL에 적재
        gateway.read(eventId, GROUP, CONSUMER, READ_COUNT)

        // 강제로 원본 메시지 삭제 (XDEL) - 페이로드 유실 상황 시뮬레이션
        redisTemplate.opsForStream<String, String>().delete("queue:order:$eventId", messageId)

        // idle 시간 경과 대기
        Thread.sleep(50)

        // when: consumer-2가 회수 시도
        val result =
            gateway.claim(
                eventPublicId = eventId,
                group = GROUP,
                consumer = "consumer-2",
                minIdleTime = java.time.Duration.ofMillis(1),
            )

        // then: 페이로드가 없으므로 claimedMessage는 비어있고 deletedIds에 포함되어야 함
        assertThat(result.claimedMessages).isEmpty()
        assertThat(result.deletedIds).containsExactly(messageId)
    }

    @Test
    fun `count보다 PEL 메시지가 많으면 nextStartId가 0-0이 아니다`() {
        val eventId = UUID.randomUUID()
        gateway.ensureGroup(eventId, GROUP)
        gateway.add(orderMessage(eventId))
        gateway.add(orderMessage(eventId))
        gateway.read(eventId, GROUP, CONSUMER, READ_COUNT)

        Thread.sleep(50)

        val result =
            gateway.claim(
                eventPublicId = eventId,
                group = GROUP,
                consumer = "consumer-2",
                minIdleTime = java.time.Duration.ofMillis(1),
                count = 1, // PEL 2개 중 1개만 회수
            )

        assertThat(result.claimedMessages).hasSize(1)
        assertThat(result.nextStartId).isNotEqualTo("0-0") // 아직 스캔할 항목이 남음

        // nextStartId로 이어서 호출하면 나머지 1개를 회수해야 함
        Thread.sleep(10)
        val result2 =
            gateway.claim(
                eventPublicId = eventId,
                group = GROUP,
                consumer = "consumer-2",
                minIdleTime = java.time.Duration.ofMillis(1),
                startId = result.nextStartId,
                count = 1,
            )
        assertThat(result2.claimedMessages).hasSize(1)
        assertThat(result2.nextStartId).isEqualTo("0-0") // 스캔 완료
    }

    @Test
    fun `Consumer Group이 없으면 claim은 DataAccessException을 던진다`() {
        val message = orderMessage()
        gateway.add(message)

        assertThatThrownBy {
            gateway.claim(
                eventPublicId = message.eventId,
                group = "non-existent-group",
                consumer = "consumer-1",
                minIdleTime = java.time.Duration.ofMillis(1),
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
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
