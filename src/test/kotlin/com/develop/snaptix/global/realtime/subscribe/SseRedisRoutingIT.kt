package com.develop.snaptix.global.realtime.subscribe

import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 실제 Redis(Testcontainers)로 subscribe → publish → dispatch 왕복을 검증한다 (PR-10).
 * emitter 까지 가지 않고, dispatch 를 기록하는 가짜 매니저로 라우팅만 본다.
 */
@Testcontainers
class SseRedisRoutingIT {
    private val mapper = jacksonObjectMapper()
    private lateinit var factory: LettuceConnectionFactory
    private lateinit var publisher: SseEventPublisher
    private val instances = mutableListOf<Instance>()

    @BeforeEach
    fun setUp() {
        factory = LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT))
        factory.afterPropertiesSet()
        factory.start()
        val template = StringRedisTemplate(factory).apply { afterPropertiesSet() }
        publisher = SseEventPublisher(template, mapper)
    }

    @AfterEach
    fun tearDown() {
        instances.forEach { it.container.stop() }
        instances.clear()
        factory.stop()
        factory.destroy()
    }

    /** 한 "서버 인스턴스" = 자체 리스너 컨테이너 + 구독자 + dispatch 기록 매니저 */
    private inner class Instance {
        val dispatched: BlockingQueue<Pair<SseChannelKey, SseEvent>> = LinkedBlockingQueue()
        val manager = RecordingManager(dispatched)
        val container =
            RedisMessageListenerContainer().apply {
                setConnectionFactory(factory)
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
        val b = instance() // 다른 채널만 관심 → KEY 미구독
        b.subscriber.subscribe(SseChannelKey("order", "other"))

        awaitDispatched(a) { publisher.publish(KEY, READY) } // A 는 받음(구독 확정 대기)

        Assertions.assertThat(b.dispatched).isEmpty() // B 는 KEY 미수신
    }

    @Test
    fun `비정상 payload 는 무시되고 리스너는 생존한다`() {
        val a = instance()
        a.subscriber.subscribe(KEY)

        // 깨진 메시지를 직접 발행해도 죽지 않고, 이후 정상 메시지는 정상 수신
        val received =
            awaitDispatched(a) {
                factory.connection.use { it.publish(KEY.redisChannel().toByteArray(), "broken".toByteArray()) }
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
        private const val REDIS_PORT = 6379
        private const val AWAIT_MS = 5000L
        private const val POLL_MS = 150L
        private val KEY = SseChannelKey("order", "order-1")
        private val READY = SseEvent.ongoing("READY_TO_PAY", mapOf("orderId" to "order-1"))

        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:8.8.0")).withExposedPorts(REDIS_PORT)
    }
}
