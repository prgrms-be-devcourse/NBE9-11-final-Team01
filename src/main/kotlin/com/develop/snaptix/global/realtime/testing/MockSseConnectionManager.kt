package com.develop.snaptix.global.realtime.testing

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.global.realtime.port.NoOpSseChannelSubscriber
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.SseChannelSubscriber
import com.develop.snaptix.global.realtime.port.StateReconstructor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * [SseConnectionManager]의 목(Mock) 구현 (PR-09).
 *
 * Redis Pub/Sub·실 스케줄러·부하 특성을 제외하고, **외부 계약 동작은 실제 구현과 동일**하게
 * 흉내 낸다(소유권 분기·비동기 dispatch·터미널 complete·재연결 재구성). 다른 도메인이
 * 실제 구현(PR-02·05) 완성을 기다리지 않고 SSE를 붙여 개발·단위 테스트하게 하기 위함이다.
 *
 * 테스트 결정성: [sendExecutor]에 `Runnable::run`(동일 스레드 실행기)을 주입하면 dispatch가
 * 동기적으로 끝나 단언이 결정적이 된다. 기본값은 비동기 풀(계약 테스트의 "비동기성" 검증용).
 */
class MockSseConnectionManager(
    private val ownershipChecker: OwnershipChecker,
    private val stateReconstructor: StateReconstructor,
    private val subscriber: SseChannelSubscriber = NoOpSseChannelSubscriber(),
    private val sendExecutor: Executor = defaultExecutor(),
    private val emitterTimeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) : SseConnectionManager {
    private val logger = KotlinLogging.logger {}

    private val registry = ConcurrentHashMap<String, SseEmitter>()

    /** 테스트 보조: 채널별 dispatch된 이벤트 기록 */
    private val emittedLog = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEvent>>()

    override fun connect(
        key: SseChannelKey,
        userId: String,
    ): SseEmitter {
        when (ownershipChecker.check(key, userId)) {
            OwnershipResult.FORBIDDEN -> throw BusinessException(ErrorCode.FORBIDDEN_ACCESS)
            OwnershipResult.NOT_FOUND -> throw BusinessException(ErrorCode.NOT_FOUND)
            OwnershipResult.OWNED -> Unit
        }

        val emitter = SseEmitter(emitterTimeoutMillis)
        val previous = registry.put(key.registryKey(), emitter)
        if (previous != null) {
            runCatching { previous.complete() } // 재연결: 기존 연결 교체 (구독은 유지)
        } else {
            subscriber.subscribe(key)
        }

        emitter.onCompletion { cleanup(key, emitter) }
        emitter.onTimeout {
            runCatching { emitter.complete() }
            cleanup(key, emitter)
        }
        emitter.onError { cleanup(key, emitter) }

        // Pub/Sub 비영속 대비: 연결 직후 현재 상태를 재구성해 1회 전송
        stateReconstructor.reconstruct(key)?.let { dispatch(key, it) }
        return emitter
    }

    override fun dispatch(
        key: SseChannelKey,
        event: SseEvent,
    ) {
        val emitter = registry[key.registryKey()] ?: return // 이 인스턴스에 연결 없음 → no-op
        emittedLog.computeIfAbsent(key.registryKey()) { CopyOnWriteArrayList() }.add(event)

        sendExecutor.execute {
            try {
                emitter.send(SseEmitter.event().name(event.name).data(event.data))
                if (event.terminal) {
                    runCatching { emitter.complete() }
                    cleanup(key, emitter)
                }
            } catch (ex: IOException) {
                logger.debug(ex) { "SSE send 실패 → 정리: ${key.registryKey()}" }
                cleanup(key, emitter)
            } catch (ex: IllegalStateException) {
                logger.debug(ex) { "SSE 연결이 이미 종료됨 → 정리: ${key.registryKey()}" }
                cleanup(key, emitter)
            }
        }
    }

    override fun close(key: SseChannelKey) {
        val emitter = registry.remove(key.registryKey()) ?: return
        runCatching { emitter.complete() }
        subscriber.unsubscribe(key)
    }

    override fun activeConnections(): Int = registry.size

    /** 등록된 Emitter와 동일할 때만 제거(identity 가드) + 구독 해제 */
    private fun cleanup(
        key: SseChannelKey,
        emitter: SseEmitter,
    ) {
        if (registry.remove(key.registryKey(), emitter)) {
            subscriber.unsubscribe(key)
        }
    }

    // ==================== 테스트 보조 API (목 전용) ====================

    /** 해당 채널로 마지막에 dispatch된 이벤트 */
    fun lastDispatched(key: SseChannelKey): SseEvent? = emittedLog[key.registryKey()]?.lastOrNull()

    /** 해당 채널로 dispatch된 이벤트 전체(순서) */
    fun emitted(key: SseChannelKey): List<SseEvent> = emittedLog[key.registryKey()].orEmpty().toList()

    /** 타임아웃 발생을 흉내 낸다(컨테이너의 onTimeout과 동일 경로). */
    fun simulateTimeout(key: SseChannelKey) {
        val emitter = registry[key.registryKey()] ?: return
        runCatching { emitter.complete() }
        cleanup(key, emitter)
    }

    /** 전송 오류 발생을 흉내 낸다(컨테이너의 onError와 동일 경로). */
    fun simulateError(key: SseChannelKey) {
        val emitter = registry[key.registryKey()] ?: return
        cleanup(key, emitter)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 8 * 60 * 1000 // 8분 (작업 명세서 D2)

        private fun defaultExecutor(): Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mock-sse-send").apply { isDaemon = true }
        }
    }
}
