package com.develop.snaptix.global.realtime.observability

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * 구조화 로깅 + Micrometer 메트릭으로 SSE 이벤트를 기록한다 (PR-08).
 *
 * 활성 연결 Gauge 는 [SseConnectionManager]의 현재 수를 읽는다. 매니저↔옵저버 순환은
 * `@Lazy`로 끊는다(매니저는 옵저버를 주입받고, 옵저버는 매니저 수를 읽음).
 */
@Component
class MicrometerSseObserver(
    registry: MeterRegistry,
    @Lazy connectionManager: SseConnectionManager,
) : SseObserver {
    private val logger = KotlinLogging.logger {}

    private val connects: Counter = registry.counter("sse.connect")
    private val disconnects: Counter = registry.counter("sse.disconnect")
    private val dispatches: Counter = registry.counter("sse.dispatch")
    private val dispatchFailures: Counter = registry.counter("sse.dispatch.failure")

    init {
        Gauge
            .builder("sse.connections.active", connectionManager) { it.activeConnections().toDouble() }
            .description("현재 인스턴스의 활성 SSE 연결 수")
            .register(registry)
    }

    override fun onConnect(key: SseChannelKey) {
        connects.increment()
        logInfo("SSE_CONNECT", "OK", key)
    }

    override fun onDisconnect(key: SseChannelKey) {
        disconnects.increment()
        logInfo("SSE_CLEANUP", "OK", key)
    }

    override fun onDispatch(
        key: SseChannelKey,
        event: SseEvent,
    ) {
        dispatches.increment()
        logInfo("SSE_DISPATCH", event.name, key)
    }

    override fun onDispatchFailure(
        key: SseChannelKey,
        cause: Throwable,
    ) {
        dispatchFailures.increment()
        logger.atWarn {
            message = "SSE"
            this.cause = cause
            payload = basePayload("SSE_DISPATCH", "ERROR", key)
        }
    }

    private fun logInfo(
        action: String,
        result: String,
        key: SseChannelKey,
    ) {
        logger.atInfo {
            message = "SSE"
            payload = basePayload(action, result, key)
        }
    }

    private fun basePayload(
        action: String,
        result: String,
        key: SseChannelKey,
    ): Map<String, Any> = mapOf(
        "action" to action,
        "result" to result,
        "resource" to key.resource,
        "id" to key.id,
        "traceId" to (MDC.get("traceId") ?: "unknown"),
    )
}
