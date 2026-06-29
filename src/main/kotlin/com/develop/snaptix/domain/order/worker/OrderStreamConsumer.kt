package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.domain.order.observability.OrderMdc
import com.develop.snaptix.domain.order.observability.OrderMetrics
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Component
class OrderStreamConsumer(
    private val orderStreamGateway: OrderStreamGateway,
    private val orderProcessor: OrderProcessor,
    private val activeEventDiscoveryPort: ActiveEventDiscoveryPort,
    private val orderStreamProperties: OrderStreamProperties,
    private val meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val log = KotlinLogging.logger {}
    private val running = AtomicBoolean(false)
    private val consumerId: String = buildConsumerId()
    private val initializedGroups = ConcurrentHashMap.newKeySet<UUID>()
    private var workerThread: Thread? = null

    @Value("\${order.consumer.auto-start:true}")
    private var autoStart: Boolean = true

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

    // ── SmartLifecycle ──────────────────────────────────────────────

    override fun start() {
        if (running.compareAndSet(false, true)) {
            workerThread =
                Thread(::consumeLoop, "order-worker")
                    .also {
                        it.isDaemon = true
                        it.start()
                    }
        }
    }

    override fun stop() {
        log.info { "Shutting down OrderStreamConsumer loop..." }
        running.set(false)
        workerThread?.let { thread ->
            thread.interrupt()
            if (thread != Thread.currentThread()) {
                thread.join(SHUTDOWN_TIMEOUT_MS)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun getPhase(): Int = Int.MAX_VALUE - LIFECYCLE_PHASE_OFFSET

    override fun isAutoStartup(): Boolean = autoStart

    // ── ContextClosed ───────────────────────────────────────────────

    @EventListener(ContextClosedEvent::class)
    fun onContextClosed() {
        running.set(false)
        workerThread?.let { thread ->
            thread.interrupt()
            if (thread != Thread.currentThread()) {
                thread.join(SHUTDOWN_TIMEOUT_MS)
            }
        }
        log.info { "OrderStreamConsumer fully stopped before infrastructure shutdown" }
    }

    // ── Consumer Loop ───────────────────────────────────────────────

    fun consumeLoop() {
        log.info { "Started OrderStreamConsumer [consumerId=$consumerId]" }
        while (running.get()) {
            runOneCycle()
        }
        log.info { "OrderStreamConsumer stopped gracefully" }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runOneCycle() {
        try {
            val processedCount = processActiveEvents()
            if (processedCount == 0 && running.get()) {
                Thread.sleep(IDLE_SLEEP_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn { "OrderStreamConsumer sleep interrupted" }
            running.set(false)
        } catch (e: Exception) {
            log.error(e) { "Unexpected error in consumer loop" }
            sleepWithBackoff()
        }
    }

    private fun processActiveEvents(): Int {
        val activeEventIds = activeEventDiscoveryPort.getActiveEvents()
        var processedCount = 0
        for (eventId in activeEventIds) {
            if (!running.get()) break
            if (initializedGroups.add(eventId)) {
                orderStreamGateway.ensureGroup(eventId, orderStreamProperties.consumerGroup)
            }
            val messages = orderStreamGateway.read(eventId, orderStreamProperties.consumerGroup, consumerId, count = 10)
            for (msg in messages) {
                processSingleMessage(eventId, msg.id, msg.body)
                processedCount++
            }
        }
        return processedCount
    }

    private fun sleepWithBackoff() {
        if (!running.get()) return
        try {
            Thread.sleep(ERROR_BACKOFF_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn { "Consumer loop interrupted during error backoff" }
            running.set(false)
        }
    }

    /**
     * 메시지 단위 처리.
     *
     * MDC 에 메시지의 userId/eventId/zoneId 를 주입하여 하위 처리 로그 전반에
     * 구조화 필드가 자동 포함되도록 한다. 처리 완료 후 반드시 MDC 를 정리한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processSingleMessage(
        eventId: UUID,
        messageId: String,
        payload: Map<String, String>,
    ) {
        try {
            val orderMessage = OrderMessage.fromStreamPayload(payload)

            // MDC 컨텍스트 — 이 메시지 처리 전반의 구조화 로그에 자동 포함
            OrderMdc.set(
                userId = orderMessage.userId,
                eventId = orderMessage.eventId,
                zoneId = orderMessage.zoneId,
            )

            log.atInfo {
                message = "XREADGROUP message received"
                this.payload =
                    mapOf(
                        "action" to "XREADGROUP",
                        "result" to "RECEIVED",
                        "messageId" to messageId,
                        "orderId" to orderMessage.orderId,
                    )
            }

            orderProcessor.process(orderMessage)

            orderStreamGateway.ack(eventId, orderStreamProperties.consumerGroup, messageId)

            // §9 메트릭: 정상 ACK 건수
            meterRegistry.counter(OrderMetrics.XACK_COUNT).increment()

            log.atInfo {
                message = "XACK completed"
                this.payload =
                    mapOf(
                        "action" to "XACK",
                        "result" to "SUCCESS",
                        "messageId" to messageId,
                        "orderId" to orderMessage.orderId,
                    )
            }
        } catch (e: IllegalArgumentException) {
            log.atError {
                message = "Terminal error — invalid payload, ACK to remove from PEL"
                cause = e
                this.payload =
                    mapOf(
                        "action" to "XREADGROUP",
                        "result" to "TERMINAL_ERROR",
                        "messageId" to messageId,
                    )
            }
            orderStreamGateway.ack(eventId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: RuntimeException) {
            log.atWarn {
                message = "Non-terminal error — left in PEL for reclaim"
                cause = e
                this.payload =
                    mapOf(
                        "action" to "XREADGROUP",
                        "result" to "NON_TERMINAL_ERROR",
                        "messageId" to messageId,
                    )
            }
        } finally {
            OrderMdc.clearOrderContext()
        }
    }

    companion object {
        private const val IDLE_SLEEP_MS = 200L
        private const val ERROR_BACKOFF_MS = 1000L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val LIFECYCLE_PHASE_OFFSET = 100
    }
}
