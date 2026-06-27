package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

class OrderStreamGatewayUnitTest {
    private val redis: StringRedisTemplate = mockk()
    private val streamOps: StreamOperations<String, String, String> = mockk()
    private val gateway = OrderStreamGateway(redis, RedisKeyFactory(), mockk<ResilientRedisExecutor>(relaxed = true))

    @BeforeEach
    fun setUp() {
        every { redis.opsForStream<String, String>() } returns streamOps
    }

    @Test
    fun `ensureGroup은 BUSYGROUP 예외를 멱등 성공으로 처리한다`() {
        val eventId = UUID.randomUUID()
        val streamKey = "queue:order:$eventId"
        val exception = DataAccessResourceFailureException("BUSYGROUP Consumer Group name already exists")
        every { streamOps.createGroup(streamKey, any<ReadOffset>(), GROUP) } throws exception

        assertThatCode { gateway.ensureGroup(eventId, GROUP) }.doesNotThrowAnyException()

        verify(exactly = 1) { streamOps.createGroup(streamKey, any<ReadOffset>(), GROUP) }
    }

    @Test
    fun `ensureGroup은 BUSYGROUP이 아닌 Redis 예외를 전파한다`() {
        val eventId = UUID.randomUUID()
        val streamKey = "queue:order:$eventId"
        val exception = DataAccessResourceFailureException("Redis connection failed")
        every { streamOps.createGroup(streamKey, any<ReadOffset>(), GROUP) } throws exception

        assertThatThrownBy { gateway.ensureGroup(eventId, GROUP) }.isSameAs(exception)

        verify(exactly = 1) { streamOps.createGroup(streamKey, any<ReadOffset>(), GROUP) }
    }

    companion object {
        private const val GROUP = "order-workers"
    }
}
