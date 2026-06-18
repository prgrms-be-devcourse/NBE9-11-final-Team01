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
 * [SseConnectionManager]의 실제 코어 구현 (PR-02).
 *
 * 인메모리 연결 레지스트리와 connect/dispatch/close/activeConnections 본체를 담당한다.
 * 수명·heartbeat(PR-03), 정리 콜백 분리(PR-04), Redis 구독(PR-05), 비동기 전송(PR-06),
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
 *
 * ## 다중 도메인 해결
 * 채널 키 `sse:{resource}:{id}`의 `resource`로 도메인별 어댑터를 선택한다. 어댑터는 빈 이름을
 * resource 로 등록한다(예: `@Component("order")`). Spring이 `Map<String, OwnershipChecker>`를
 * 빈 이름 → 빈으로 주입하므로 `[key.resource]`로 조회한다.
 *
 * ## 기동 안전성
 * 생성자 기본값 덕분에 어댑터·구독자 빈이 아직 없어도(PR-05·07 머지 전) 기동된다.
 * [subscriber]는 빈이 있으면 주입, 없으면 [NoOpSseChannelSubscriber]를 사용한다.
 */
@Component
class InMemorySseConnectionManager(
    private val ownershipCheckers: Map<String, OwnershipChecker> = emptyMap(),
    private val stateReconstructors: Map<String, StateReconstructor> = emptyMap(),
    private val subscriber: SseChannelSubscriber = NoOpSseChannelSubscriber(),
) : SseConnectionManager {
    private val logger = KotlinLogging.logger {}

    private val registry = ConcurrentHashMap<String, SseEmitter>()

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

        val emitter = SseEmitter(DEFAULT_TIMEOUT_MS)
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
        stateReconstructors[key.resource]?.reconstruct(key)?.let { dispatch(key, it) }
        return emitter
    }

    override fun dispatch(
        key: SseChannelKey,
        event: SseEvent,
    ) {
        val emitter = registry[key.registryKey()] ?: return // 이 인스턴스에 연결 없음 → no-op

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
        val emitter = registry.remove(key.registryKey()) ?: return
        runCatching { emitter.complete() }
        subscriber.unsubscribe(key)
    }

    override fun activeConnections(): Int = registry.size

    /** 등록된 Emitter와 동일할 때만 제거(identity 가드) + 구독 해제. 멱등. */
    private fun cleanup(
        key: SseChannelKey,
        emitter: SseEmitter,
    ) {
        if (registry.remove(key.registryKey(), emitter)) {
            subscriber.unsubscribe(key)
        }
    }

    companion object {
        // 타임아웃 봉투 기본값. PR-03에서 RealtimeProperties 로 외부화 예정. (작업 명세서 D2)
        const val DEFAULT_TIMEOUT_MS: Long = 8 * 60 * 1000
    }
}
