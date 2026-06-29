// 위치: src/test/kotlin/com/develop/snaptix/global/aop/aspect/RateLimitAspectTest.kt
package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RateLimit
import com.develop.snaptix.global.exception.redis.RateLimitExceededException
import com.develop.snaptix.global.redis.gateway.RateLimitRedisGateway
import com.develop.snaptix.global.redis.gateway.RateLimitResult
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.dao.QueryTimeoutException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration

@ExtendWith(MockKExtension::class)
class RateLimitAspectTest {
    @MockK
    private lateinit var gateway: RateLimitRedisGateway

    private lateinit var aspect: RateLimitAspect
    private lateinit var service: TestService

    @BeforeEach
    fun setUp() {
        aspect = RateLimitAspect(gateway)
        service = proxyOf<TestService>(TestServiceImpl())

        val request = MockHttpServletRequest()
        request.addHeader("X-Forwarded-For", CLIENT_IP)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `허용되면 원본 메서드를 실행하고 게이트웨이에 한도를 위임한다`() {
        every { gateway.hit(CLIENT_IP, PER_SECOND, PER_MINUTE) } returns
            RateLimitResult(allowed = true, retryAfter = null)

        val result = service.call()

        assertThat(result).isEqualTo("OK")
        verify(exactly = 1) { gateway.hit(CLIENT_IP, PER_SECOND, PER_MINUTE) }
    }

    @Test
    fun `차단되면 RateLimitExceededException을 던진다`() {
        every {
            gateway.hit(any(), any(), any())
        } returns RateLimitResult(allowed = false, retryAfter = Duration.ofSeconds(RETRY_AFTER_SECONDS))

        assertThrows<RateLimitExceededException> {
            service.call()
        }
    }

    @Test
    fun `Redis 장애(DataAccessException) 시 차단하지 않고 진행한다`() {
        every { gateway.hit(any(), any(), any()) } throws QueryTimeoutException("Redis timeout")

        val result = service.call()

        assertThat(result).isEqualTo("OK")
    }

    // reified로 인터페이스 타입을 명시 — JDK 프록시는 구현 클래스로 캐스트 불가
    private inline fun <reified T : Any> proxyOf(target: Any): T {
        val factory = AspectJProxyFactory(target)
        factory.addAspect(aspect)
        return T::class.java.cast(factory.getProxy())
    }

    interface TestService {
        fun call(): String
    }

    class TestServiceImpl : TestService {
        @RateLimit(limitPerSecond = 5, limitPerMinute = 20)
        override fun call(): String = "OK"
    }

    companion object {
        private const val CLIENT_IP = "203.0.113.7"
        private const val PER_SECOND = 5
        private const val PER_MINUTE = 20
        private const val RETRY_AFTER_SECONDS = 60L
    }
}
