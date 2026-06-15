package com.develop.snaptix.global.aop.aspect
import global.aop.annotation.RateLimit
import global.aop.aspect.RateLimitAspect
import global.exception.redis.RateLimitExceededException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class RateLimitAspectTest {
    private val redisTemplate: StringRedisTemplate = mockk()
    private lateinit var proxy: TestService

    @BeforeEach
    fun setUp() {
        val factory = AspectJProxyFactory(TestService())
        factory.addAspect(RateLimitAspect(redisTemplate))
        proxy = factory.getProxy()

        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(
                MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" },
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `초당 제한 초과 시 RateLimitExceededException이 발생하고 retryAfterSeconds가 1이다`() {
        stubRedis(secCount = 6L, minCount = 1L)

        val exception =
            assertThrows<RateLimitExceededException> {
                proxy.rateLimitedMethod()
            }

        assertThat(exception.retryAfterSeconds).isEqualTo(1L)
    }

    @Test
    fun `분당 제한 초과 시 RateLimitExceededException이 발생하고 retryAfterSeconds가 60이다`() {
        stubRedis(secCount = 1L, minCount = 21L)

        val exception =
            assertThrows<RateLimitExceededException> {
                proxy.rateLimitedMethod()
            }

        assertThat(exception.retryAfterSeconds).isEqualTo(60L)
    }

    @Test
    fun `초당 분당 제한 미초과 시 원본 메서드가 정상 실행된다`() {
        stubRedis(secCount = 3L, minCount = 10L)

        val result = proxy.rateLimitedMethod()

        assertThat(result).isEqualTo("success")
    }

    @Test
    fun `X-Forwarded-For 헤더가 있으면 첫 번째 IP를 기준으로 Rate Limit 키를 생성한다`() {
        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(
                MockHttpServletRequest().apply {
                    addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1")
                    remoteAddr = "10.0.0.1"
                },
            ),
        )
        every {
            redisTemplate.execute(any<RedisScript<Long>>(), match { "203.0.113.42" in it.first() }, any<Any>())
        } returns 1L

        proxy.rateLimitedMethod()

        // sec, min 두 번 모두 실제 클라이언트 IP(203.0.113.42) 기준으로 호출되어야 함
        verify(exactly = 2) {
            redisTemplate.execute(any<RedisScript<Long>>(), match { "203.0.113.42" in it.first() }, any<Any>())
        }
    }

    @Test
    fun `Redis 예외 발생 시 Rate Limit 검사를 스킵하고 원본 메서드를 실행한다`() {
        every {
            redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any<Any>())
        } throws DataAccessResourceFailureException("Redis 연결 실패")

        // 예외 없이 정상 실행되어야 함
        val result = proxy.rateLimitedMethod()

        assertThat(result).isEqualTo("success")
    }

    @Test
    fun `@RateLimit이 없는 메서드는 Aspect가 개입하지 않는다`() {
        val result = proxy.noAnnotationMethod()

        assertThat(result).isEqualTo("no-aspect")
        verify(exactly = 0) {
            redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any<Any>())
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private fun stubRedis(
        secCount: Long,
        minCount: Long,
    ) {
        every {
            redisTemplate.execute(any<RedisScript<Long>>(), match { "sec" in it.first() }, any<Any>())
        } returns secCount
        every {
            redisTemplate.execute(any<RedisScript<Long>>(), match { "min" in it.first() }, any<Any>())
        } returns minCount
    }

    open class TestService {
        @RateLimit
        open fun rateLimitedMethod(): String = "success"

        open fun noAnnotationMethod(): String = "no-aspect"
    }
}
