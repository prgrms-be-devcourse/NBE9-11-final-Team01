package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
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
            // workerThread 내부에서 stop()이 호출되는 경우(테스트 mock 등) self-join 방지
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
        // ContextClosedEvent는 SmartLifecycle 처리 이전에 발행됨.
        // interrupt() 후 join()으로 스레드 완전 종료를 대기해야
        // Lettuce SmartLifecycle stop 시점에 워커가 Redis I/O를 하지 않음.
        // join() 없이 interrupt()만 하면 스레드가 아직 실행 중인 채로
        // Lettuce event loop가 종료되어 RejectedExecutionException + OOM이 발생함.
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

    // [Fix] CyclomaticComplexMethod: 루프 본문을 runOneCycle()로 추출하여 복잡도 감소
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
        if (!running.get()) return // shutdown 중이면 재시도 불필요
        try {
            Thread.sleep(ERROR_BACKOFF_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn { "Consumer loop interrupted during error backoff" }
            running.set(false)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 비터미널 에러 포착을 위해 의도적으로 최상위 런타임 예외를 캐치함
    private fun processSingleMessage(eventId: UUID, messageId: String, payload: Map<String, String>) {
        try {
            val orderMessage = OrderMessage.fromStreamPayload(payload)
            orderProcessor.process(orderMessage)
            orderStreamGateway.ack(eventId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: IllegalArgumentException) {
            log.error(e) { "[TERMINAL_ERROR] Invalid payload format. messageId=$messageId" }
            orderStreamGateway.ack(eventId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: RuntimeException) {
            log.warn(e) { "[NON_TERMINAL_ERROR] Processing failed. Left in PEL. messageId=$messageId" }
        }
    }

    companion object {
        private const val IDLE_SLEEP_MS = 200L
        private const val ERROR_BACKOFF_MS = 1000L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val LIFECYCLE_PHASE_OFFSET = 100
    }
}
