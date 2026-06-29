// 위치: src/test/kotlin/com/develop/snaptix/global/aop/aspect/IdempotencyAspectTest.kt
package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.aop.annotation.IdempotencyTarget
import com.develop.snaptix.global.aop.annotation.Idempotent
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.exception.redis.IdempotencyConflictException
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

@ExtendWith(MockKExtension::class)
class IdempotencyAspectTest {
    @MockK
    private lateinit var gateway: IdempotencyRedisGateway

    private lateinit var aspect: IdempotencyAspect
    private lateinit var testService: TestService

    private val orderId = "11111111-1111-1111-1111-111111111111"
    private val eventId = "22222222-2222-2222-2222-222222222222"
    private val orderUuid: UUID = UUID.fromString(orderId)
    private val eventUuid: UUID = UUID.fromString(eventId)

    @BeforeEach
    fun setUp() {
        aspect = IdempotencyAspect(gateway)
        testService = proxyOf<TestService>(TestServiceImpl())
        setSecurityContext(USER_ID)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Nested
    inner class `최초 요청` {
        @Test
        fun `선점 성공 시 원본 메서드를 실행하고 게이트웨이에 위임한다`() {
            every { gateway.tryAcquire(USER_ID, eventUuid, orderUuid) } returns true

            val result = testService.enqueue(request())

            assertThat(result).isEqualTo("SUCCESS")
            verify(exactly = 1) { gateway.tryAcquire(USER_ID, eventUuid, orderUuid) }
        }
    }

    @Nested
    inner class `중복 요청` {
        @Test
        fun `선점 실패 시 IdempotencyConflictException(DUPLICATE_ORDER, 409)을 던진다`() {
            every { gateway.tryAcquire(any(), any(), any()) } returns false

            val ex =
                assertThrows<IdempotencyConflictException> {
                    testService.enqueue(request())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.DUPLICATE_ORDER)
            assertThat(ex.httpStatus.value()).isEqualTo(409)
        }
    }

    @Nested
    inner class `인게스트 실패 정리` {
        @Test
        fun `proceed 예외 시 compare-and-delete를 자기 식별자로 위임하고 예외를 다시 던진다`() {
            val failingService = proxyOf<TestService>(FailingTestServiceImpl())
            every { gateway.tryAcquire(any(), any(), any()) } returns true
            every { gateway.compareAndDelete(any(), any(), any()) } returns true

            val ex =
                assertThrows<IllegalStateException> {
                    failingService.enqueue(request())
                }

            assertThat(ex.message).isEqualTo("큐 적재 실패")
            verify(exactly = 1) { gateway.compareAndDelete(USER_ID, eventUuid, orderUuid) }
        }
    }

    @Nested
    inner class `Redis 장애 fail-open` {
        @Test
        fun `tryAcquire가 DataAccessException이면 차단하지 않고 진행한다`() {
            every { gateway.tryAcquire(any(), any(), any()) } throws QueryTimeoutException("Redis timeout")

            val result = testService.enqueue(request())

            assertThat(result).isEqualTo("SUCCESS")
        }
    }

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

    companion object {
        private const val USER_ID = 1L
    }
}
