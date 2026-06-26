package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Component
class OrderStreamConsumer(
    private val orderStreamGateway: OrderStreamGateway,
    private val orderProcessor: OrderProcessor,
    private val activeEventDiscoveryPort: ActiveEventDiscoveryPort,
) {
    private val log = KotlinLogging.logger {}
    private val isRunning = AtomicBoolean(false)
    private val consumerId: String = buildConsumerId()

    /** 호스트명과 랜덤 suffix를 조합하여 재기동 시 중복을 방지하는 고유 ID 발급 */
    private fun buildConsumerId(): String {
        val hostname =
            try {
                InetAddress.getLocalHost().hostName
            } catch (e: UnknownHostException) {
                // [Fix] 구체적인 예외 처리 및 SwallowedException 방지를 위한 로깅
                log.warn(e) { "Failed to resolve hostname, using fallback." }
                "unknown-host"
            }
        val suffix = UUID.randomUUID().toString().take(6)
        return "$hostname-$suffix"
    }

    /** 부팅 시점에 애플리케이션 스레드풀 블로킹을 막기 위해 별도 스레드에서 무한 루프 실행 */
    @EventListener(ApplicationReadyEvent::class)
    @Async("workerTaskExecutor") // 별도 Async 스레드풀 설정 필요
    @Suppress("TooGenericExceptionCaught") // [Fix] 워커 메인 루프 방어를 위해 Exception catch 허용
    fun consumeLoop() {
        if (!isRunning.compareAndSet(false, true)) return
        log.info { "Started OrderStreamConsumer [consumerId=$consumerId]" }

        while (isRunning.get()) {
            try {
                val activeEventIds = activeEventDiscoveryPort.getActiveEvents()
                var processedCount = 0

                for (eventId in activeEventIds) {
                    if (!isRunning.get()) break

                    // 1. 그룹 초기화 (BUSYGROUP 멱등 무시)
                    orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)

                    // 2. 메시지 읽기 (OrderStreamGateway.read는 Non-blocking 읽기)
                    val messages = orderStreamGateway.read(eventId, CONSUMER_GROUP, consumerId, count = 10)

                    for (msg in messages) {
                        processSingleMessage(eventId, msg.id, msg.body)
                        processedCount++
                    }
                }

                // 모든 활성 스트림에 메시지가 없으면 CPU Spin 방지를 위해 짧게 대기
                if (processedCount == 0 && isRunning.get()) {
                    Thread.sleep(IDLE_SLEEP_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn { "OrderStreamConsumer sleep interrupted" }
                break
            } catch (e: Exception) {
                log.error(e) { "Unexpected error in consumer loop" }
                Thread.sleep(ERROR_BACKOFF_MS) // 에러 발생 시 무한 루프 방지를 위한 백오프 대기
            }
        }
        log.info { "OrderStreamConsumer stopped gracefully" }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processSingleMessage(
        eventId: UUID,
        messageId: String,
        payload: Map<String, String>,
    ) {
        try {
            // 3. 강타입 역직렬화 및 처리 위임
            val orderMessage = OrderMessage.fromStreamPayload(payload)
            orderProcessor.process(orderMessage)

            // 4. 처리 성공 시 XACK 호출 (PEL에서 제거)
            orderStreamGateway.ack(eventId, CONSUMER_GROUP, messageId)
        } catch (e: IllegalArgumentException) {
            // 필드 누락/오타 등 터미널(영구적) 예외: 다시 시도해도 처리 불가하므로 ACK (필요시 DLQ 보관)
            log.error(e) { "[TERMINAL_ERROR] Invalid payload format. messageId=$messageId" }
            orderStreamGateway.ack(eventId, CONSUMER_GROUP, messageId)
        } catch (e: Exception) {
            // 그 외 비터미널(일시적) 예외: XACK 생략하여 PEL에 남김 -> 추후 9번 이슈의 XAUTOCLAIM이 회수
            log.warn(e) { "[NON_TERMINAL_ERROR] Processing failed. Left in PEL. messageId=$messageId" }
        }
    }

    /** 그레이스풀 셧다운: 루프 플래그 해제 */
    @PreDestroy
    fun shutdown() {
        log.info { "Shutting down OrderStreamConsumer loop..." }
        isRunning.set(false)
    }

    // [Fix] Magic Number 해결을 위한 상수 분리
    companion object {
        private const val CONSUMER_GROUP = "order-workers"
        private const val IDLE_SLEEP_MS = 200L
        private const val ERROR_BACKOFF_MS = 1000L
    }
}
