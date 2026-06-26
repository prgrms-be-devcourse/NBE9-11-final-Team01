package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@ConditionalOnProperty(
    name = ["snaptix.order.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@Component
class OrderStreamConsumer(
    private val orderStreamGateway: OrderStreamGateway,
    private val orderProcessor: OrderProcessor,
    private val activeEventDiscoveryPort: ActiveEventDiscoveryPort,
) {
    private val log = KotlinLogging.logger {}
    private val isRunning = AtomicBoolean(false)
    private val consumerId: String = buildConsumerId()
    private val initializedGroups = ConcurrentHashMap.newKeySet<UUID>()

    private fun buildConsumerId(): String {
        val hostname =
            try {
                InetAddress.getLocalHost().hostName
            } catch (e: UnknownHostException) {
                log.warn(e) { "Failed to resolve hostname, using fallback." }
                "unknown-host"
            }
        val suffix = UUID.randomUUID().toString().take(6)
        return "$hostname-$suffix"
    }

    @EventListener(ApplicationReadyEvent::class)
    @Async("workerTaskExecutor")
    @Suppress("TooGenericExceptionCaught")
    fun consumeLoop() {
        if (!isRunning.compareAndSet(false, true)) return
        log.info { "Started OrderStreamConsumer [consumerId=$consumerId]" }

        while (isRunning.get()) {
            try {
                val activeEventIds = activeEventDiscoveryPort.getActiveEvents()
                var processedCount = 0

                for (eventId in activeEventIds) {
                    if (!isRunning.get()) break

                    if (initializedGroups.add(eventId)) {
                        orderStreamGateway.ensureGroup(eventId, CONSUMER_GROUP)
                    }

                    val messages = orderStreamGateway.read(eventId, CONSUMER_GROUP, consumerId, count = 10)

                    for (msg in messages) {
                        processSingleMessage(eventId, msg.id, msg.body)
                        processedCount++
                    }
                }

                if (processedCount == 0 && isRunning.get()) {
                    Thread.sleep(IDLE_SLEEP_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn { "OrderStreamConsumer sleep interrupted" }
                isRunning.set(false) // [Fix] break 대신 플래그 변경으로 자연스러운 루프 종료
            } catch (e: Exception) {
                log.error(e) { "Unexpected error in consumer loop" }

                try {
                    Thread.sleep(ERROR_BACKOFF_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn { "Consumer loop interrupted during error backoff" }
                    isRunning.set(false) // [Fix] break 대신 플래그 변경
                }
            }
        }
        log.info { "OrderStreamConsumer stopped gracefully" }
    }

    @Suppress("TooGenericExceptionCaught") // 비터미널 에러 포착을 위해 의도적으로 최상위 런타임 예외를 캐치함
    private fun processSingleMessage(eventId: UUID, messageId: String, payload: Map<String, String>) {
        try {
            val orderMessage = OrderMessage.fromStreamPayload(payload)
            orderProcessor.process(orderMessage)
            orderStreamGateway.ack(eventId, CONSUMER_GROUP, messageId)
        } catch (e: IllegalArgumentException) {
            log.error(e) { "[TERMINAL_ERROR] Invalid payload format. messageId=$messageId" }
            orderStreamGateway.ack(eventId, CONSUMER_GROUP, messageId)
        } catch (e: RuntimeException) {
            log.warn(e) { "[NON_TERMINAL_ERROR] Processing failed. Left in PEL. messageId=$messageId" }
        }
    }

    @PreDestroy
    fun shutdown() {
        log.info { "Shutting down OrderStreamConsumer loop..." }
        isRunning.set(false)
    }

    companion object {
        private const val CONSUMER_GROUP = "order-workers"
        private const val IDLE_SLEEP_MS = 200L
        private const val ERROR_BACKOFF_MS = 1000L
    }
}
