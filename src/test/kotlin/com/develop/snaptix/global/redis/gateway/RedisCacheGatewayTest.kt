package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.UUID

class RedisCacheGatewayTest {
    private val redis: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val executor: ResilientRedisExecutor = mockk()

    // 키 생성을 위한 Factory
    private val keyFactory = RedisKeyFactory()

    private lateinit var redisCacheGateway: RedisCacheGateway

    @BeforeEach
    fun setUp() {
        every { redis.opsForValue() } returns valueOps
        redisCacheGateway = RedisCacheGateway(redis, executor)
    }

    @Test
    fun `get - 정상적으로 CACHE_GET 액션과 함께 조회한다`() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)
        val expectedJson = """{"name":"콘서트"}"""

        every { executor.execute<String?>(eq(RedisAction.CACHE_GET), any()) } answers {
            secondArg<() -> String?>().invoke()
        }
        every { valueOps.get(key) } returns expectedJson

        val result = redisCacheGateway.get(key)

        assertThat(result).isEqualTo(expectedJson)
        verify(exactly = 1) { valueOps.get(key) }
        verify(exactly = 1) { executor.execute(RedisAction.CACHE_GET, any()) }
    }

    @Test
    fun `put - 정상적으로 CACHE_SET 액션과 함께 적재한다`() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)
        val value = """{"name":"뮤지컬"}"""
        val ttl = Duration.ofSeconds(3600)

        every { executor.execute<Unit>(eq(RedisAction.CACHE_SET), any()) } answers {
            secondArg<() -> Unit>().invoke()
        }
        every { valueOps.set(key, value, ttl) } just runs

        redisCacheGateway.put(key, value, ttl)

        verify(exactly = 1) { valueOps.set(key, value, ttl) }
        verify(exactly = 1) { executor.execute(RedisAction.CACHE_SET, any()) }
    }

    @Test
    fun `evict - 정상적으로 CACHE_INVALIDATE 액션과 함께 키를 삭제한다`() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)

        every { executor.execute<Unit>(eq(RedisAction.CACHE_INVALIDATE), any()) } answers {
            secondArg<() -> Unit>().invoke()
        }
        every { redis.delete(key) } returns true

        redisCacheGateway.evict(key)

        verify(exactly = 1) { redis.delete(key) }
        verify(exactly = 1) { executor.execute(RedisAction.CACHE_INVALIDATE, any()) }
    }

    @Test
    fun `서킷 브레이커 개방 시 발생하는 예외를 그대로 상위로 전파한다`() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)

        every {
            executor.execute<String?>(eq(RedisAction.CACHE_GET), any())
        } throws RedisUnavailableException()

        assertThrows<RedisUnavailableException> {
            redisCacheGateway.get(key)
        }

        verify(exactly = 0) { valueOps.get(any()) }
    }
}
