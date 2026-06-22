package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RedisCacheAside
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

/**
 * CacheAsideAspect 단위 테스트.
 *
 * Spring 컨텍스트 없이 AspectJProxyFactory + MockK 로만 구성한다.
 * FakeEventService / FakeEventResponse 는 이 테스트에서만 쓰이는 스텁이므로
 * 테스트 클래스 안에 중첩 선언한다.
 */
class CacheAsideAspectTest {
    // ── 의존성 Mock ───────────────────────────────────────────────────
    private val redis: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    // ── 프록시 대상 (spyk → jp.proceed() 호출 여부 검증) ──────────────
    private lateinit var target: FakeEventService
    private lateinit var proxy: FakeEventService

    @BeforeEach
    fun setUp() {
        every { redis.opsForValue() } returns valueOps

        target = spyk(FakeEventService())

        val factory = AspectJProxyFactory(target)
        factory.addAspect(CacheAsideAspect(redis, objectMapper))
        proxy = factory.getProxy()
    }

    // ── 1. 캐시 HIT ───────────────────────────────────────────────────

    @Test
    fun `캐시 HIT - proceed 미호출, 역직렬화된 값 반환`() {
        val expected = FakeEventResponse(id = "pub-001", name = "콘서트 A")
        val json = objectMapper.writeValueAsString(expected)

        every { valueOps.get("event:info:pub-001") } returns json

        val result = proxy.getEventInfo("pub-001")

        assertThat(result).isEqualTo(expected)
        // jp.proceed() 미호출 → target 메서드 미실행
        verify(exactly = 0) { target.getEventInfo(any()) }
    }

    // ── 2. 캐시 MISS ──────────────────────────────────────────────────

    @Test
    fun `캐시 MISS - proceed 1회 호출 후 SET 적재`() {
        val dbResult = FakeEventResponse(id = "pub-002", name = "뮤지컬 B")

        every { valueOps.get("event:info:pub-002") } returns null
        every { target.getEventInfo("pub-002") } returns dbResult
        every { valueOps.set(any(), any(), any<Duration>()) } just runs

        val result = proxy.getEventInfo("pub-002")

        assertThat(result).isEqualTo(dbResult)
        verify(exactly = 1) { target.getEventInfo("pub-002") }
        verify { valueOps.set("event:info:pub-002", any(), Duration.ofSeconds(3600)) }
    }

    // ── 3. Redis GET 장애 → DB 폴백 ──────────────────────────────────

    @Test
    fun `Redis GET 장애 시 503 없이 DB 폴백`() {
        val dbResult = FakeEventResponse(id = "pub-003", name = "전시 C")

        every { valueOps.get("event:info:pub-003") } throws QueryTimeoutException("redis timeout")
        every { target.getEventInfo("pub-003") } returns dbResult

        val result = assertDoesNotThrow { proxy.getEventInfo("pub-003") }

        assertThat(result).isEqualTo(dbResult)
        verify(exactly = 1) { target.getEventInfo("pub-003") }
    }

    // ── 4. SET 실패 → DB 결과는 정상 반환 ────────────────────────────

    @Test
    fun `캐시 SET 실패 시 DB 결과 정상 반환`() {
        val dbResult = FakeEventResponse(id = "pub-004", name = "페스티벌 D")

        every { valueOps.get("event:info:pub-004") } returns null
        every { target.getEventInfo("pub-004") } returns dbResult
        every {
            valueOps.set(any(), any(), any<Duration>())
        } throws QueryTimeoutException("redis timeout")

        val result = assertDoesNotThrow { proxy.getEventInfo("pub-004") }

        assertThat(result).isEqualTo(dbResult)
    }

    // ── 5. 손상 캐시 → DEL 후 proceed ────────────────────────────────

    @Test
    fun `손상 캐시 - DEL 수행 후 DB 폴백`() {
        val dbResult = FakeEventResponse(id = "pub-005", name = "오페라 E")

        every { valueOps.get("event:info:pub-005") } returns "{ invalid json %%% }"
        every { redis.delete("event:info:pub-005") } returns true
        every { target.getEventInfo("pub-005") } returns dbResult

        val result = proxy.getEventInfo("pub-005")

        assertThat(result).isEqualTo(dbResult)
        verify { redis.delete("event:info:pub-005") }
        verify(exactly = 1) { target.getEventInfo("pub-005") }
    }

    // ── 6. 어노테이션 없는 메서드 → Aspect 미개입 ─────────────────────

    @Test
    fun `어노테이션 없는 메서드는 Redis에 접근하지 않음`() {
        val result = proxy.getWithoutAnnotation("pub-006")

        assertThat(result.id).isEqualTo("pub-006")
        verify(exactly = 0) { redis.opsForValue() }
    }

    // ── 테스트용 스텁 ─────────────────────────────────────────────────

    /** CGLIB 프록시 적용을 위해 클래스와 메서드 모두 open */
    @Suppress("MaxLineLength")
    open class FakeEventService {
        @RedisCacheAside(keyPrefix = "event:info", ttlSeconds = 3600)
        open fun getEventInfo(publicId: String): FakeEventResponse = FakeEventResponse(id = publicId, name = "DB 결과")

        open fun getWithoutAnnotation(publicId: String): FakeEventResponse =
            FakeEventResponse(id = publicId, name = "캐시 없음")
    }

    data class FakeEventResponse(
        val id: String,
        val name: String,
    )
}
