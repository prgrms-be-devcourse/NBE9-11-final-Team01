package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.aop.annotation.IdempotencyTarget
import com.develop.snaptix.global.aop.annotation.Idempotent
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.redis.IdempotencyConflictException
import com.develop.snaptix.global.redis.script.COMPARE_AND_DELETE_SCRIPT
import com.develop.snaptix.global.security.auth.AuthenticatedUser
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration

@ExtendWith(MockKExtension::class)
class IdempotencyAspectTest {
    @MockK
    private lateinit var redis: StringRedisTemplate

    @MockK
    private lateinit var valueOperations: ValueOperations<String, String>

    private lateinit var aspect: IdempotencyAspect
    private lateinit var testService: TestService

    // RedisScriptConfig가 등록하는 빈과 동일한 실제 스크립트를 주입한다.
    private val compareAndDeleteScript: RedisScript<Long> =
        DefaultRedisScript(COMPARE_AND_DELETE_SCRIPT, Long::class.java)

    private val userId = 1L
    private val orderId = "order-uuid-1234"
    private val eventId = "event-uuid-5678"
    private val expectedKey = "idempotency:order:$userId:$eventId"

    @BeforeEach
    fun setUp() {
        aspect = IdempotencyAspect(redis, compareAndDeleteScript)
        every { redis.opsForValue() } returns valueOperations

        testService = proxyOf<TestService>(TestServiceImpl())
        setSecurityContext(userId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── 1. 최초 요청 ────────────────────────────────────────────────────────

    @Nested
    inner class `최초 요청` {
        @Test
        fun `SET NX 성공 시 원본 메서드를 실행하고 결과를 반환한다`() {
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true

            val result = testService.enqueue(request())

            assertThat(result).isEqualTo("SUCCESS")
        }

        @Test
        fun `키는 idempotency-order-userId-eventId, 값은 orderId, TTL은 8분으로 SET NX 한다`() {
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true

            testService.enqueue(request())

            verify(exactly = 1) {
                valueOperations.setIfAbsent(
                    expectedKey,
                    orderId,
                    Duration.ofMinutes(8),
                )
            }
        }
    }

    // ── 2. 중복 요청 ────────────────────────────────────────────────────────

    @Nested
    inner class `중복 요청` {
        @Test
        fun `SET NX 실패(키 선점됨) 시 IdempotencyConflictException을 던진다`() {
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns false

            assertThrows<IdempotencyConflictException> {
                testService.enqueue(request())
            }
        }

        @Test
        fun `예외 코드는 DUPLICATE_ORDER이고 HTTP 상태는 409이다`() {
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns false

            val ex =
                assertThrows<IdempotencyConflictException> {
                    testService.enqueue(request())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.DUPLICATE_ORDER)
            assertThat(ex.httpStatus.value()).isEqualTo(409)
        }
    }

    // ── 3. 값 검증 — SET 값이 orderId와 일치 ────────────────────────────────

    @Nested
    inner class `SET 값 검증` {
        @Test
        fun `SET NX의 value는 전달된 orderId 그대로다`() {
            val capturedValue = mutableListOf<String>()
            every {
                valueOperations.setIfAbsent(any(), capture(capturedValue), any<Duration>())
            } returns true

            testService.enqueue(request())

            assertThat(capturedValue).containsExactly(orderId)
        }

        @Test
        fun `다른 orderId를 가진 요청은 각각 독립적인 값으로 SET 된다`() {
            val orderId1 = "order-aaa"
            val orderId2 = "order-bbb"
            val capturedValues = mutableListOf<String>()

            every {
                valueOperations.setIfAbsent(any(), capture(capturedValues), any<Duration>())
            } returns true

            testService.enqueue(request(orderId = orderId1))
            testService.enqueue(request(orderId = orderId2))

            assertThat(capturedValues).containsExactly(orderId1, orderId2)
        }
    }

    // ── 4. 인게스트 실패 시 compare-and-delete ──────────────────────────────

    @Nested
    inner class `인게스트 실패 정리` {
        @Test
        fun `proceed 예외 발생 시 compare-and-delete가 호출된다`() {
            val failingService = proxyOf<TestService>(FailingTestServiceImpl())
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true
            every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any()) } returns 1L

            assertThrows<IllegalStateException> {
                failingService.enqueue(request())
            }

            verify(exactly = 1) {
                redis.execute(any<RedisScript<Long>>(), listOf(expectedKey), orderId)
            }
        }

        @Test
        fun `proceed 예외는 그대로 다시 던져진다`() {
            val failingService = proxyOf<TestService>(FailingTestServiceImpl())
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true
            every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any()) } returns 1L

            val ex =
                assertThrows<IllegalStateException> {
                    failingService.enqueue(request())
                }

            assertThat(ex.message).isEqualTo("큐 적재 실패")
        }
    }

    // ── 5. compare-and-delete 인자 정확성 (타 주문 키 보호) ─────────────────

    @Nested
    inner class `compare-and-delete 인자 검증` {
        @Test
        fun `CAD에 전달되는 orderId는 자신의 orderId만이다`() {
            val failingService = proxyOf<TestService>(FailingTestServiceImpl())
            val capturedArgs = mutableListOf<String>()

            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true
            every {
                redis.execute(any<RedisScript<Long>>(), any<List<String>>(), capture(capturedArgs))
            } returns 0L // 불일치 — 타 주문이 키 재점유 (DEL 안 함)

            runCatching { failingService.enqueue(request(orderId = orderId)) }

            assertThat(capturedArgs).containsExactly(orderId)
        }

        @Test
        fun `CAD에 전달되는 키는 자신의 멱등 키다`() {
            val failingService = proxyOf<TestService>(FailingTestServiceImpl())
            val capturedKeys = mutableListOf<List<String>>()

            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true
            every {
                redis.execute(any<RedisScript<Long>>(), capture(capturedKeys), any())
            } returns 1L

            runCatching { failingService.enqueue(request()) }

            assertThat(capturedKeys.first()).containsExactly(expectedKey)
        }
    }

    // ── 6. Redis 장애 — fail-open ────────────────────────────────────────────

    @Nested
    inner class `Redis 장애 fail-open` {
        @Test
        fun `DataAccessException 발생 시 proceed를 실행한다`() {
            every {
                valueOperations.setIfAbsent(any(), any(), any<Duration>())
            } throws QueryTimeoutException("Redis timeout")

            val result = testService.enqueue(request())

            assertThat(result).isEqualTo("SUCCESS")
        }

        @Test
        fun `Redis 장애 시 SET NX를 재시도하지 않고 즉시 진행한다`() {
            every {
                valueOperations.setIfAbsent(any(), any(), any<Duration>())
            } throws QueryTimeoutException("Redis timeout")

            testService.enqueue(request())

            // setIfAbsent는 정확히 1회만 시도 (재시도 없음)
            verify(exactly = 1) { valueOperations.setIfAbsent(any(), any(), any<Duration>()) }
        }
    }

    // ── 7. 추출 실패 ────────────────────────────────────────────────────────

    @Nested
    inner class `추출 실패` {
        @Test
        fun `SecurityContext가 비어있으면 UNAUTHORIZED BusinessException을 던진다`() {
            SecurityContextHolder.clearContext()

            assertThatThrownBy { testService.enqueue(request()) }
                .isInstanceOf(BusinessException::class.java)
                .satisfies({ ex ->
                    assertThat((ex as BusinessException).errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
                })
        }

        @Test
        fun `IdempotencyTarget 인자가 없는 메서드는 IllegalArgumentException을 던진다`() {
            every { valueOperations.setIfAbsent(any(), any(), any<Duration>()) } returns true

            val service = proxyOf<NoTargetService>(NoTargetServiceImpl())

            assertThrows<IllegalArgumentException> {
                service.doSomething()
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun request(
        orderId: String = this.orderId,
        eventId: String = this.eventId,
    ) = TestOrderRequest(orderId = orderId, eventId = eventId)

    private fun setSecurityContext(userId: Long) {
        val principal = AuthenticatedUser(userId = userId, role = UserRole.USER)
        val auth = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        SecurityContextHolder.getContext().authentication = auth
    }

    // reified로 인터페이스 타입을 명시 — JDK 프록시는 구현 클래스로 캐스트 불가
    private inline fun <reified T : Any> proxyOf(target: Any): T {
        val factory = AspectJProxyFactory(target)
        factory.addAspect(aspect)
        return T::class.java.cast(factory.getProxy())
    }

    // ── test doubles ────────────────────────────────────────────────────────

    data class TestOrderRequest(
        override val orderId: String,
        override val eventId: String,
    ) : IdempotencyTarget

    interface TestService {
        fun enqueue(request: TestOrderRequest): String
    }

    /** 정상 케이스 */
    class TestServiceImpl : TestService {
        @Idempotent
        override fun enqueue(request: TestOrderRequest): String = "SUCCESS"
    }

    /** 인게스트 실패 케이스 */
    class FailingTestServiceImpl : TestService {
        @Idempotent
        override fun enqueue(request: TestOrderRequest): String = throw IllegalStateException("큐 적재 실패")
    }

    /** IdempotencyTarget 인자 없는 케이스 */
    interface NoTargetService {
        fun doSomething(): String
    }

    class NoTargetServiceImpl : NoTargetService {
        @Idempotent
        override fun doSomething(): String = "NO_TARGET"
    }
}
