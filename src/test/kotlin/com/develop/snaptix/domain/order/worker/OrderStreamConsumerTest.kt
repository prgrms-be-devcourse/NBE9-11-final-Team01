package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.StreamMessage
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

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
        // stop() 내부에서 join()을 수행하므로, 이후 스레드 누수 검사가 안전하게 가능
        consumer.stop()
        clearAllMocks()
        assertNoThreadLeak()
    }

    private fun assertNoThreadLeak() {
        val leakedThreads =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name == "order-worker" }
                .filter { it.isAlive }
        assertThat(leakedThreads)
            .withFailMessage { "테스트 후 order-worker 스레드 누수 감지" }
            .isEmpty()
    }

    /**
     * 루프를 한 번만 실행하도록 하는 헬퍼 함수
     * 첫 번째 호출: eventId 리스트 반환
     * 두 번째 호출: consumer.stop() 호출 후 emptyList 반환 → 루프 자연 종료
     *
     * 주의: consumer.stop()이 백그라운드 스레드(workerThread) 내부에서 호출되므로
     * OrderStreamConsumer.stop()에서 self-join을 건너뛰는 처리가 필요하다.
     */
    private fun mockLoopToRunOnce(eventId: UUID) {
        var callCount = 0
        every { activeEventDiscoveryPort.getActiveEvents() } answers {
            if (callCount++ == 0) {
                listOf(eventId)
            } else {
                consumer.stop()
                emptyList()
            }
        }
    }

    /**
     * 백그라운드 스레드의 루프가 종료될 때까지 폴링 대기
     */
    private fun awaitConsumerStop(timeout: Duration = Duration.ofSeconds(2)) {
        assertTimeout(timeout) {
            while (consumer.isRunning()) Thread.sleep(10)
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
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        @DisplayName("정상적인 메시지가 스트림에 존재하는 경우, 메시지를 역직렬화하여 처리하고 XACK를 호출한다")
        fun successCase() {
            // Arrange
            mockLoopToRunOnce(eventId)
            every { orderStreamGateway.read(any(), any(), any(), any()) } returns
                listOf(
                    StreamMessage(streamMessageId, validPayload),
                )

            // Act
            consumer.start()
            awaitConsumerStop()

            // Assert
            verify(exactly = 1) { orderStreamGateway.ensureGroup(eventId, "order-workers") }
            verify(exactly = 1) {
                orderProcessor.process(
                    match {
                        it.orderId == orderId &&
                            it.userId == userId &&
                            it.eventId == eventId &&
                            it.zoneId == zoneId
                    },
                )
            }
            verify(exactly = 1) { orderStreamGateway.ack(eventId, "order-workers", streamMessageId) }
        }

        @Test
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
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
            consumer.start()
            awaitConsumerStop()

            // Assert
            verify(exactly = 0) { orderProcessor.process(any()) } // 역직렬화 실패로 위임되지 않음
            verify(exactly = 1) { orderStreamGateway.ack(eventId, "order-workers", streamMessageId) } // 찌꺼기 방지 ACK
        }

        @Test
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
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
            consumer.start()
            awaitConsumerStop()

            // Assert
            verify(exactly = 1) { orderProcessor.process(any()) }
            verify(exactly = 0) { orderStreamGateway.ack(any(), any(), any()) } // ACK 생략!
        }

        @Test
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        @DisplayName("수행할 활성 이벤트(큐)가 없는 경우, 아무 처리 없이 대기(sleep) 후 루프를 순회한다")
        fun emptyActiveEventsCase() {
            // Arrange
            var callCount = 0
            every { activeEventDiscoveryPort.getActiveEvents() } answers {
                if (callCount++ == 0) {
                    emptyList()
                } else {
                    consumer.stop()
                    emptyList()
                }
            }

            // Act
            consumer.start()
            awaitConsumerStop()

            // Assert
            verify(exactly = 0) { orderStreamGateway.ensureGroup(any(), any()) }
            verify(exactly = 0) { orderStreamGateway.read(any(), any(), any(), any()) }
        }
    }

    @Nested
    @DisplayName("SmartLifecycle 종료 동작은")
    inner class ShutdownTest {
        @Test
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        @DisplayName("stop() 호출 시 소비자 루프가 즉시 종료된다")
        fun stopTerminatesLoopPromptly() {
            // Arrange
            every { activeEventDiscoveryPort.getActiveEvents() } returns emptyList()
            consumer.start()
            Thread.sleep(100) // 루프가 한 번 돌 시간 확보

            // Act
            val elapsed = measureTimeMillis { consumer.stop() }

            // Assert
            assertThat(elapsed).isLessThan(500)
            assertThat(consumer.isRunning()).isFalse()
        }

        @Test
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        @DisplayName("에러 발생 중 stop() 호출 시 ERROR_BACKOFF_MS(1000ms)를 기다리지 않고 즉시 종료된다")
        fun stopDuringErrorBackoffTerminatesImmediately() {
            // Arrange: Redis 중단 상황 시뮬레이션
            every { activeEventDiscoveryPort.getActiveEvents() } throws RuntimeException("Redis down")
            consumer.start()
            Thread.sleep(100) // 루프가 backoff sleep에 진입할 시간 확보

            // Act
            val elapsed = measureTimeMillis { consumer.stop() }

            // Assert: interrupt로 즉시 깨어나므로 ERROR_BACKOFF_MS(1000ms)보다 훨씬 짧아야 함
            assertThat(elapsed).isLessThan(1_000L)
            assertThat(consumer.isRunning()).isFalse()
        }

        @Test
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        @DisplayName("start()가 여러 번 호출되어도 루프는 한 번만 실행된다")
        fun startIsIdempotent() {
            // Arrange
            every { activeEventDiscoveryPort.getActiveEvents() } returns emptyList()

            // Act
            consumer.start()
            consumer.start() // 두 번째 호출은 compareAndSet(false, true) 실패로 무시됨

            consumer.stop()

            // Assert: order-worker 스레드가 1개만 생성되었는지는 @AfterEach의 assertNoThreadLeak()에서 검증
            assertThat(consumer.isRunning()).isFalse()
        }
    }
}
