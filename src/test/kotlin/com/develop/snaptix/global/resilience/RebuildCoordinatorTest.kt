package com.develop.snaptix.global.resilience

import com.develop.snaptix.global.redis.gateway.RebuildLockRedisGateway
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * RebuildCoordinator 단위 테스트 (MockK).
 *
 * Redis 접근은 [RebuildLockRedisGateway] 로 위임되므로(RedisAccessRules 준수), 본 단위 테스트는
 * **위임 정확성**(키·TTL 전달)과 **acquire/release 가 동일 instanceId 토큰을 쓰는지**만 검증한다.
 * 실제 SET NX 단일 획득/compare-and-delete 정확성은 RebuildLockRedisGatewayTest(통합)가 본다.
 */
class RebuildCoordinatorTest {
    private val gateway = mockk<RebuildLockRedisGateway>()
    private val properties = ReconcileProperties().apply { rebuildLockTtl = Duration.ofMinutes(5) }
    private val coordinator = RebuildCoordinator(gateway, properties)

    @Test
    fun `should_rebuild_lock_키와_TTL로_위임하고_결과반환_when_tryAcquire하면`() {
        every { gateway.tryAcquire("rebuild:lock", any(), Duration.ofMinutes(5)) } returns true

        val acquired = coordinator.tryAcquire()

        assertThat(acquired).isTrue()
        verify(exactly = 1) { gateway.tryAcquire("rebuild:lock", any(), Duration.ofMinutes(5)) }
    }

    @Test
    fun `should_false반환_when_락_미획득이면`() {
        every { gateway.tryAcquire(any(), any(), any()) } returns false

        assertThat(coordinator.tryAcquire()).isFalse()
    }

    @Test
    fun `should_acquire와_release가_동일_instanceId_토큰_사용_when_같은_코디네이터면`() {
        val acquireToken = slot<String>()
        val releaseToken = slot<String>()
        every { gateway.tryAcquire("rebuild:lock", capture(acquireToken), any()) } returns true
        every { gateway.release("rebuild:lock", capture(releaseToken)) } just Runs

        coordinator.tryAcquire()
        coordinator.release()

        assertThat(releaseToken.captured).isEqualTo(acquireToken.captured)
    }
}
