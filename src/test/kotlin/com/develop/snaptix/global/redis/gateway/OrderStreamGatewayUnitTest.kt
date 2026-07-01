package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import com.fasterxml.jackson.core.JsonProcessingException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

class OrderStreamGatewayUnitTest {
    private val redis: StringRedisTemplate = mockk()
    private val streamOps: StreamOperations<String, String, String> = mockk()
    private val gateway =
        OrderStreamGateway(
            redis = redis,
            keys = RedisKeyFactory(),
            executor = mockk<ResilientRedisExecutor>(relaxed = true),
            xAutoClaimScript = mockk<RedisScript<String>>(),
            objectMapper = mockk<ObjectMapper>(),
        )

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

    // ── parseClaimResult 파싱 실패 경로 ───────────────────────────────────────

    /**
     * XAUTOCLAIM Lua 스크립트가 파싱 불가능한 JSON을 반환한 경우의 복구 로직 검증.
     *
     * ## 테스트 전략
     * - [ResilientRedisExecutor]를 block을 실제 실행하는 방식으로 mock
     * - [redis.execute]가 잘못된 JSON 문자열을 반환하도록 설정
     * - [ObjectMapper.readTree]가 [JsonProcessingException]을 throw하도록 mock
     * → parseClaimResult의 catch 블록 진입, 빈 [ClaimResult] + fallbackStartId 반환을 검증
     */
    @Test
    fun `claim — XAUTOCLAIM 응답 JSON 파싱 실패 시 빈 ClaimResult와 fallbackStartId를 반환한다`() {
        val localScript = mockk<RedisScript<String>>()
        val localObjectMapper = mockk<ObjectMapper>()
        val localExecutor = mockk<ResilientRedisExecutor>()
        val localGateway =
            OrderStreamGateway(
                redis = redis,
                keys = RedisKeyFactory(),
                executor = localExecutor,
                xAutoClaimScript = localScript,
                objectMapper = localObjectMapper,
            )

        // executor가 block을 실제로 실행하도록 설정
        every { localExecutor.execute<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as Function0<*>).invoke()
        }
        // Lua 스크립트가 파싱 불가한 JSON 반환
        every {
            redis.execute(localScript, any<List<String>>(), *anyVararg<Any>())
        } returns "INVALID{{JSON"
        // ObjectMapper 파싱 실패 시뮬레이션
        // mockk<JsonProcessingException>(relaxed = true)는 getCause() 무한 재귀로 StackOverflow 유발 —
        // 익명 서브클래스로 직접 인스턴스를 생성해 회피 (JsonProcessingException 생성자가 protected이므로)
        every { localObjectMapper.readTree(any<String>()) } throws
            object : JsonProcessingException("test: XAUTOCLAIM 응답 파싱 실패") {}

        val fallbackStartId = "0-0"
        val result =
            localGateway.claim(
                eventPublicId = UUID.randomUUID(),
                group = GROUP,
                consumer = CONSUMER,
                minIdleTime = Duration.ofMillis(1),
                startId = fallbackStartId,
            )

        assertThat(result.claimedMessages).isEmpty()
        assertThat(result.deletedIds).isEmpty()
        assertThat(result.nextStartId).isEqualTo(fallbackStartId)
    }

    companion object {
        private const val GROUP = "order-workers"
        private const val CONSUMER = "consumer-1"
    }
}
