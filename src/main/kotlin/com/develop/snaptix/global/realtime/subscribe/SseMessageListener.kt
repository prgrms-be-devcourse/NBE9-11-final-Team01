package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * Pub/Sub 수신 메시지를 [SseEvent]로 역직렬화해 [SseConnectionManager.dispatch]로 전달한다. (PR-05)
 *
 * 단일 리스너 인스턴스가 모든 구독 채널을 처리한다(onMessage 의 channel 로 식별).
 *
 * 순환 의존 차단: 매니저 → RedisSseSubscriber → 본 리스너 → 매니저 의 사이클을
 * `@Lazy SseConnectionManager`로 끊는다.
 */
@Component
class SseMessageListener(
    @Lazy private val connectionManager: SseConnectionManager,
    private val objectMapper: ObjectMapper,
) : MessageListener {
    private val logger = KotlinLogging.logger {}

    override fun onMessage(
        message: Message,
        pattern: ByteArray?,
    ) {
        val channel = String(message.channel, StandardCharsets.UTF_8)
        val key = parseChannel(channel)
        if (key == null) {
            logger.warn { "SSE 채널 파싱 실패 → 무시: channel=$channel" }
            return
        }

        val body = String(message.body, StandardCharsets.UTF_8)
        val wire =
            try {
                objectMapper.readValue(body, SseMessage::class.java)
            } catch (ex: JacksonException) {
                logger.warn(ex) { "SSE 메시지 역직렬화 실패 → 무시: channel=$channel" }
                return
            }

        connectionManager.dispatch(key, SseEvent(wire.name, wire.data ?: EMPTY_DATA, wire.terminal))
    }

    companion object {
        private const val PREFIX = "sse:"
        private val EMPTY_DATA = emptyMap<String, Any>()

        /** `sse:{resource}:{id}` → [SseChannelKey]. 형식 위반 시 null. */
        fun parseChannel(channel: String): SseChannelKey? {
            if (!channel.startsWith(PREFIX)) return null
            val rest = channel.removePrefix(PREFIX)
            val sep = rest.indexOf(':')
            return when {
                sep <= 0 || sep == rest.lastIndex -> null
                else -> {
                    val resource = rest.substring(0, sep)
                    val id = rest.substring(sep + 1)
                    runCatching { SseChannelKey(resource, id) }.getOrNull()
                }
            }
        }
    }
}
