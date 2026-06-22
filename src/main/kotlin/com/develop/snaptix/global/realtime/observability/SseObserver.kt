package com.develop.snaptix.global.realtime.observability

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent

/**
 * SSE 코어의 관측 hook (PR-08). 코어는 이 인터페이스만 알고, 로깅/메트릭 구현은 주입받는다.
 * AOP 대신 collaborator 를 쓰는 이유: connect→내부 cleanup, 비동기 dispatch 등
 * Spring AOP 가 일관되게 발화하지 못하는 지점을 정확히 계측하기 위함.
 */
interface SseObserver {
    fun onConnect(key: SseChannelKey)

    fun onDisconnect(key: SseChannelKey)

    fun onDispatch(
        key: SseChannelKey,
        event: SseEvent,
    )

    fun onDispatchFailure(
        key: SseChannelKey,
        cause: Throwable,
    )
}

/** 기본 무동작 구현(테스트/미설정 시). */
object NoOpSseObserver : SseObserver {
    override fun onConnect(key: SseChannelKey) = Unit

    override fun onDisconnect(key: SseChannelKey) = Unit

    override fun onDispatch(
        key: SseChannelKey,
        event: SseEvent,
    ) = Unit

    override fun onDispatchFailure(
        key: SseChannelKey,
        cause: Throwable,
    ) = Unit
}
