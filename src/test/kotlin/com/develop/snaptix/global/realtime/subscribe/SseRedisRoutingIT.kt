package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 실제 Redis(Testcontainers)로 subscribe → publish → dispatch 왕복을 검증한다 (PR-10).
 * emitter 까지 가지 않고, dispatch 를 기록하는 가짜 매니저로 라우팅만 본다.
 */
class SseRedisRoutingIT : IntegrationTestSupport() {
    private val mapper = jacksonObjectMapper()
    private lateinit var publisher: SseEventPublisher
    private val instances = mutableListOf<Instance>()

    @BeforeEach
    fun setUp() {
        // ✅ 팩토리를 수동으로 생성하고 start() 할 필요가 전혀 없음
        // ✅ redisTemplate은 부모 클래스(IntegrationTestSupport)에 이미 protected로 선언되어 있으므로 바로 사용
        publisher = SseEventPublisher(redisTemplate, mapper)
    }

    @AfterEach
    fun tearDown() {
        // 개별 인스턴스들의 리스너 컨테이너만 안전하게 정리
        instances.forEach {
            it.container.stop()
            it.container.destroy()
        }
        instances.clear()

        // ❌ factory.stop(), factory.destroy() 삭제
        // -> connectionFactory는 스프링이 관리하는 싱글톤 빈이므로 테스트에서 강제로 끄면 다른 테스트가 망가짐!
    }

    /** 한 "서버 인스턴스" = 자체 리스너 컨테이너 + 구독자 + dispatch 기록 매니저 */
    private inner class Instance {
        val dispatched: BlockingQueue<Pair<SseChannelKey, SseEvent>> = LinkedBlockingQueue()
        val manager = RecordingManager(dispatched)
        val container =
            RedisMessageListenerContainer().apply {
                // 부모 클래스의 redisTemplate에서 팩토리를 직접 가져옵니다.
                setConnectionFactory(redisTemplate.connectionFactory!!)
                afterPropertiesSet()
                start()
            }
        val subscriber = RedisSseSubscriber(container, SseMessageListener(manager, mapper))
    }

    private fun instance() = Instance().also { instances += it }

    @Test
    fun `구독한 인스턴스가 발행 메시지를 dispatch 한다`() {
        val a = instance()
        a.subscriber.subscribe(KEY)

        val received = awaitDispatched(a) { publisher.publish(KEY, READY) }

        Assertions.assertThat(received.first).isEqualTo(KEY)
        Assertions.assertThat(received.second.name).isEqualTo("READY_TO_PAY")
    }

    @Test
    fun `구독하지 않은 인스턴스는 받지 않는다(라우팅)`() {
        val a = instance().also { it.subscriber.subscribe(KEY) }
        val b = instance()
        b.subscriber.subscribe(SseChannelKey("order", "other"))

        awaitDispatched(a) { publisher.publish(KEY, READY) }

        Assertions.assertThat(b.dispatched).isEmpty()
    }

    @Test
    fun `비정상 payload 는 무시되고 리스너는 생존한다`() {
        val a = instance()
        a.subscriber.subscribe(KEY)

        val received =
            awaitDispatched(a) {
                redisTemplate.connectionFactory!!.connection.use {
                    it.publish(KEY.redisChannel().toByteArray(), "broken".toByteArray())
                }
                publisher.publish(KEY, READY)
            }
        Assertions.assertThat(received.second.name).isEqualTo("READY_TO_PAY")
    }

    /** 구독 등록 race 대비: 받을 때까지 재발행(멱등) 후 결과 반환. */
    private fun awaitDispatched(
        instance: Instance,
        publish: () -> Unit,
    ): Pair<SseChannelKey, SseEvent> {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            publish()
            val msg = instance.dispatched.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (msg != null) return msg
        }
        error("메시지를 수신하지 못했습니다(timeout)")
    }

    /** dispatch 호출만 기록하는 가짜 매니저. */
    private class RecordingManager(
        private val sink: BlockingQueue<Pair<SseChannelKey, SseEvent>>,
    ) : SseConnectionManager {
        override fun connect(
            key: SseChannelKey,
            userId: String,
        ): SseEmitter = throw UnsupportedOperationException()

        override fun dispatch(
            key: SseChannelKey,
            event: SseEvent,
        ) {
            sink.add(key to event)
        }

        override fun close(key: SseChannelKey) = Unit

        override fun activeConnections(): Int = 0
    }

    companion object {
        private const val AWAIT_MS = 5000L
        private const val POLL_MS = 150L
        private val KEY = SseChannelKey("order", "order-1")
        private val READY = SseEvent.ongoing("READY_TO_PAY", mapOf("orderId" to "order-1"))
    }
}
