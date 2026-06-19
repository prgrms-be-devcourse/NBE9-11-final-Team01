package com.develop.snaptix.global.realtime

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.realtime.port.NoOpSseChannelSubscriber
import com.develop.snaptix.global.realtime.port.OwnershipChecker
import com.develop.snaptix.global.realtime.port.OwnershipResult
import com.develop.snaptix.global.realtime.port.SseChannelSubscriber
import com.develop.snaptix.global.realtime.port.StateReconstructor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * [SseConnectionManager]의 실제 코어 구현 (PR-02, PR-03에서 수명/heartbeat 반영).
 *
 * 인메모리 연결 레지스트리와 connect/dispatch/close/activeConnections 본체를 담당한다.
 * 정리 콜백 분리(PR-04), Redis 구독(PR-05), 비동기 전송(PR-06),
 * 로깅/메트릭(PR-08)은 후속 PR에서 끼운다.
 *
 * ## 레지스트리가 왜 인스턴스 로컬인가 (Redis에 두지 않는 이유)
 * 레지스트리가 들고 있는 [SseEmitter]는 **이 JVM이 점유한 살아있는 HTTP 연결 핸들**이다.
 * 직렬화 불가하며 실제 소켓이 이 인스턴스에 고정돼 있어 Redis로 옮길 수 없다(옮겨도 무의미).
 * 다중 서버 동기화는 emitter 공유가 아니라 **Redis Pub/Sub 라우팅**으로 한다:
 *  - connect 시 `sse:{resource}:{id}` 채널을 동적 구독([SseChannelSubscriber], PR-05)
 *  - 워커가 그 채널로 Publish → **연결을 점유한 인스턴스만** 수신 → 로컬 [dispatch]
 *  - 다른 인스턴스는 해당 채널 미구독(또는 [dispatch] no-op)
 * 인스턴스 장애로 연결이 끊기면 클라이언트 재연결 + [StateReconstructor]로 상태 재구성한다.
 * (ERD Redis Key 명세: `sse:order:{orderId}` Pub/Sub / Story 4.2)
 */
@Component
class InMemorySseConnectionManager(
    private val ownershipCheckers: Map<String, OwnershipChecker> = emptyMap(),
    private val stateReconstructors: Map<String, StateReconstructor> = emptyMap(),
    private val subscriber: SseChannelSubscriber = NoOpSseChannelSubscriber(),
    private val properties: RealtimeProperties = RealtimeProperties(),
) : SseConnectionManager {
    private val logger = KotlinLogging.logger {}

    // 키를 SseChannelKey(data class)로 직접 사용 → heartbeat 순회 시 채널 키로 구독 해제 가능
    private val registry = ConcurrentHashMap<SseChannelKey, SseEmitter>()

    override fun connect(
        key: SseChannelKey,
        userId: String,
    ): SseEmitter {
        val checker =
            ownershipCheckers[key.resource]
                ?: error("등록된 OwnershipChecker 가 없습니다: resource=${key.resource}")

        when (checker.check(key, userId)) {
            OwnershipResult.FORBIDDEN -> throw BusinessException(ErrorCode.FORBIDDEN_ACCESS)
            OwnershipResult.NOT_FOUND -> throw BusinessException(ErrorCode.NOT_FOUND)
            OwnershipResult.OWNED -> Unit
        }

        val emitter = SseEmitter(properties.timeoutMillis()) // 타임아웃 봉투(설정 외부화, PR-03)
        val previous = registry.put(key, emitter)
        if (previous != null) {
            runCatching { previous.complete() } // 재연결: 기존 연결 교체 (구독은 유지)
        } else {
            subscriber.subscribe(key)
        }

        registerLifecycle(key, emitter)

        // Pub/Sub 비영속 대비: 연결 직후 현재 상태를 재구성해 1회 전송
        stateReconstructors[key.resource]?.reconstruct(key)?.let { dispatch(key, it) }
        return emitter
    }

    override fun dispatch(
        key: SseChannelKey,
        event: SseEvent,
    ) {
        val emitter = registry[key] ?: return // 이 인스턴스에 연결 없음 → no-op

        // NOTE: 본 PR은 동기 전송. PR-06에서 전용 TaskExecutor 비동기 전송으로 교체.
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

    override fun close(key: SseChannelKey) {
        val emitter = registry.remove(key) ?: return
        runCatching { emitter.complete() }
        subscriber.unsubscribe(key)
    }

    override fun activeConnections(): Int = registry.size

    /**
     * 활성 연결에 주석 ping 을 보내 죽은 연결을 조기 감지·정리한다. (PR-03 HeartbeatScheduler 가 주기 호출)
     * ConcurrentHashMap 순회는 weakly-consistent 라 동시 등록/제거와 안전하다.
     */
    fun heartbeat() {
        registry.forEach { (key, emitter) ->
            try {
                emitter.send(SseEmitter.event().comment("ping"))
            } catch (ex: IOException) {
                logger.debug(ex) { "heartbeat 실패 → 정리: ${key.registryKey()}" }
                cleanup(key, emitter)
            } catch (ex: IllegalStateException) {
                logger.debug(ex) { "heartbeat 대상이 이미 종료됨 → 정리: ${key.registryKey()}" }
                cleanup(key, emitter)
            }
        }
    }

    /**
     * 모든 종료 경로(정상 완료·타임아웃·에러)에서 누수 없이 정리되도록 콜백을 등록한다.
     * onTimeout 은 Spring 이 자동 complete 하지 않으므로 직접 complete 후 정리한다.
     */
    private fun registerLifecycle(
        key: SseChannelKey,
        emitter: SseEmitter,
    ) {
        emitter.onCompletion { cleanup(key, emitter) }
        emitter.onTimeout {
            runCatching { emitter.complete() }
            cleanup(key, emitter)
        }
        emitter.onError { cleanup(key, emitter) }
    }

    /** 등록된 Emitter와 동일할 때만 제거(identity 가드) + 구독 해제. 멱등. */
    private fun cleanup(
        key: SseChannelKey,
        emitter: SseEmitter,
    ) {
        if (registry.remove(key, emitter)) {
            subscriber.unsubscribe(key)
        }
    }
}
