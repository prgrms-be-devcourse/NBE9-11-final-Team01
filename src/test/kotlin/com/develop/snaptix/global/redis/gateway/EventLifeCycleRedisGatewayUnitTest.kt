package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * [EventLifeCycleRedisGateway] 단위 테스트 — 에러 핸들러 경로(catch 블록) 검증.
 *
 * 이 Gateway는 다른 Gateway들과 달리 [DataAccessException] / [RedisUnavailableException]을
 * 외부로 전파하지 않고 안전한 기본값(0L / null)으로 폴백한다.
 * 해당 폴백 로직이 실제로 동작하는지 검증한다.
 *
 * ## 전략
 * - [ResilientRedisExecutor]를 mock하여 각 예외를 직접 throw
 * - 통합 테스트 컨테이너 없이 빠르게 실행
 */
class EventLifeCycleRedisGatewayUnitTest {
    private val redis: StringRedisTemplate = mockk(relaxed = true)
    private val executor: ResilientRedisExecutor = mockk()
    private val gateway = EventLifeCycleRedisGateway(redis = redis, executor = executor)

    private val streamKey = "queue:order:test-event"
    private val groupName = "order-workers"

    // ── getStreamLength ────────────────────────────────────────────────────────

    @Test
    fun `getStreamLength — DataAccessException 발생 시 0L을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws
            DataAccessResourceFailureException("Redis connection failed")

        assertThat(gateway.getStreamLength(streamKey)).isEqualTo(0L)
    }

    @Test
    fun `getStreamLength — 서킷 오픈(RedisUnavailableException) 시 0L을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws RedisUnavailableException()

        assertThat(gateway.getStreamLength(streamKey)).isEqualTo(0L)
    }

    // ── getGroupLastDeliveredId ────────────────────────────────────────────────

    @Test
    fun `getGroupLastDeliveredId — DataAccessException 발생 시 null을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws
            DataAccessResourceFailureException("Redis connection failed")

        assertThat(gateway.getGroupLastDeliveredId(streamKey, groupName)).isNull()
    }

    @Test
    fun `getGroupLastDeliveredId — 서킷 오픈(RedisUnavailableException) 시 null을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws RedisUnavailableException()

        assertThat(gateway.getGroupLastDeliveredId(streamKey, groupName)).isNull()
    }

    // ── getStreamLastGeneratedId ───────────────────────────────────────────────

    @Test
    fun `getStreamLastGeneratedId — DataAccessException 발생 시 null을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws
            DataAccessResourceFailureException("Redis connection failed")

        assertThat(gateway.getStreamLastGeneratedId(streamKey)).isNull()
    }

    @Test
    fun `getStreamLastGeneratedId — 서킷 오픈(RedisUnavailableException) 시 null을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws RedisUnavailableException()

        assertThat(gateway.getStreamLastGeneratedId(streamKey)).isNull()
    }

    // ── getStreamGroupInfo ─────────────────────────────────────────────────────

    @Test
    fun `getStreamGroupInfo — DataAccessException 발생 시 null을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws
            DataAccessResourceFailureException("Redis connection failed")

        assertThat(gateway.getStreamGroupInfo(streamKey, groupName)).isNull()
    }

    @Test
    fun `getStreamGroupInfo — 서킷 오픈(RedisUnavailableException) 시 null을 반환한다`() {
        every { executor.execute<Any?>(any(), any()) } throws RedisUnavailableException()

        assertThat(gateway.getStreamGroupInfo(streamKey, groupName)).isNull()
    }
}
