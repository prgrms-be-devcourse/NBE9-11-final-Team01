package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.StreamMessage
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

@DisplayName("OrderStreamConsumer 테스트")
class OrderStreamConsumerTest {
    private val orderStreamGateway = mockk<OrderStreamGateway>(relaxed = true)
    private val orderProcessor = mockk<OrderProcessor>(relaxed = true)
    private val activeEventDiscoveryPort = mockk<ActiveEventDiscoveryPort>()

    private lateinit var consumer: OrderStreamConsumer

    @BeforeEach
    fun setUp() {
        consumer =
            OrderStreamConsumer(
                orderStreamGateway = orderStreamGateway,
                orderProcessor = orderProcessor,
                activeEventDiscoveryPort = activeEventDiscoveryPort,
            )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * 무한 루프 테스트를 위한 헬퍼 함수
     * 첫 번째 호출 시에는 정상적으로 eventId 리스트를 반환하고,
     * 두 번째 호출 시에는 consumer.shutdown()을 호출하여 루프를 안전하게 종료시킨다.
     */
    private fun mockLoopToRunOnce(eventId: UUID) {
        var callCount = 0
        every { activeEventDiscoveryPort.getActiveEvents() } answers {
            if (callCount++ == 0) {
                listOf(eventId)
            } else {
                consumer.shutdown() // 루프 플래그 해제
                emptyList()
            }
        }
    }

    @Nested
    @DisplayName("consumeLoop 메서드는")
    inner class ConsumeLoopTest {
        private val eventId = UUID.randomUUID()
        private val orderId = UUID.randomUUID()
        private val userId = 1L
        private val zoneId = 1L
        private val streamMessageId = "1620000000000-0"

        private val validPayload =
            mapOf(
                OrderMessage.FIELD_ORDER_ID to orderId.toString(),
                OrderMessage.FIELD_USER_ID to userId.toString(),
                OrderMessage.FIELD_EVENT_ID to eventId.toString(),
                OrderMessage.FIELD_ZONE_ID to zoneId.toString(),
            )

        @Test
        @DisplayName("정상적인 메시지가 스트림에 존재하는 경우, 메시지를 역직렬화하여 처리하고 XACK를 호출한다")
        fun successCase() {
            // Arrange
            mockLoopToRunOnce(eventId)
            every { orderStreamGateway.read(any(), any(), any(), any()) } returns
                listOf(
                    StreamMessage(streamMessageId, validPayload),
                )

            // Act
            consumer.consumeLoop()

            // Assert
            verify(exactly = 1) { orderStreamGateway.ensureGroup(eventId, "order-workers") }
            verify(exactly = 1) {
                orderProcessor.process(
                    match {
                        it.orderId == orderId && it.userId == userId && it.eventId == eventId && it.zoneId == zoneId
                    },
                )
            }
            verify(exactly = 1) { orderStreamGateway.ack(eventId, "order-workers", streamMessageId) }
        }

        @Test
        @DisplayName("필드가 누락된 잘못된 형식의 메시지(터미널 예외)가 유입된 경우, 처리를 중단하고 XACK를 호출하여 PEL에서 즉시 제거한다")
        fun terminalErrorCase() {
            // Arrange
            mockLoopToRunOnce(eventId)
            val invalidPayload = mapOf("wrongField" to "wrongData")
            every { orderStreamGateway.read(any(), any(), any(), any()) } returns
                listOf(
                    StreamMessage(streamMessageId, invalidPayload),
                )

            // Act
            consumer.consumeLoop()

            // Assert
            verify(exactly = 0) { orderProcessor.process(any()) } // 역직렬화 실패로 위임되지 않음
            verify(exactly = 1) { orderStreamGateway.ack(eventId, "order-workers", streamMessageId) } // 찌꺼기 방지 ACK
        }

        @Test
        @DisplayName("메시지 처리 중 일시적인 오류(비터미널 예외)가 발생한 경우, XACK를 호출하지 않고 PEL에 남겨둔다")
        fun nonTerminalErrorCase() {
            // Arrange
            mockLoopToRunOnce(eventId)
            every { orderStreamGateway.read(any(), any(), any(), any()) } returns
                listOf(
                    StreamMessage(streamMessageId, validPayload),
                )
            every { orderProcessor.process(any()) } throws RuntimeException("DB Connection Timeout")

            // Act
            consumer.consumeLoop()

            // Assert
            verify(exactly = 1) { orderProcessor.process(any()) }
            verify(exactly = 0) { orderStreamGateway.ack(any(), any(), any()) } // ACK 생략!
        }

        @Test
        @DisplayName("수행할 활성 이벤트(큐)가 없는 경우, 아무 처리 없이 대기(sleep) 후 루프를 순회한다")
        fun emptyActiveEventsCase() {
            // Arrange
            var callCount = 0
            every { activeEventDiscoveryPort.getActiveEvents() } answers {
                if (callCount++ == 0) {
                    emptyList() // 빈 목록 반환
                } else {
                    consumer.shutdown()
                    emptyList()
                }
            }

            // Act
            consumer.consumeLoop()

            // Assert
            verify(exactly = 0) { orderStreamGateway.ensureGroup(any(), any()) }
            verify(exactly = 0) { orderStreamGateway.read(any(), any(), any(), any()) }
        }
    }
}
