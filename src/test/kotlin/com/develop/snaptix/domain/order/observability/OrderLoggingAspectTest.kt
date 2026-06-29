package com.develop.snaptix.domain.order.observability

import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory

/**
 * [OrderLoggingAspect] 단위 테스트.
 *
 * ## 전략
 * - [AspectJProxyFactory]로 Spring 컨텍스트 없이 AOP 동작만 격리 검증
 * - [CacheAsideAspectTest] / [IdempotencyAspectTest]와 동일한 프록시 패턴 적용
 *
 * ## 검증 범위
 * 1. 정상 실행 — proceed()가 호출되고 반환값이 그대로 전달된다
 * 2. 예외 재전파 — RuntimeException이 호출부로 전달된다 (삼켜지면 안 됨)
 * 3. BusinessException 재전파 — WARN 경로도 예외를 삼키지 않는다
 * 4. MDC 공존 — MDC에 값이 있어도 Aspect 실행에 영향이 없다
 * 5. 어노테이션 없는 메서드 — Aspect가 개입하지 않는다
 *
 * ## 비검증 범위
 * 로그 필드(action / result / executionTimeMs) 내용은 SLF4J 테스트 appender 없이는
 * 검증이 어렵고 별도 라이브러리 의존성이 생기므로 제외한다.
 * AOP의 핵심 계약(proceed 호출 여부 / 예외 전파)을 검증하는 것으로 충분하다.
 */
class OrderLoggingAspectTest {
    private lateinit var aspect: OrderLoggingAspect

    @BeforeEach
    fun setUp() {
        aspect = OrderLoggingAspect()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 정상 실행
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    inner class `정상 실행` {
        @Test
        fun `@LogAction 메서드 정상 실행 시 proceed가 호출되고 반환값이 그대로 전달된다`() {
            val proxy = proxyOf<FakeService>(FakeServiceImpl())

            val result = proxy.doAction()

            assertThat(result).isEqualTo("SUCCESS")
        }

        @Test
        fun `MDC에 값이 설정된 상태에서도 Aspect가 정상 실행된다`() {
            val proxy = proxyOf<FakeService>(FakeServiceImpl())
            MDC.put(OrderMdc.TRACE_ID, "trace-abc")
            MDC.put(OrderMdc.USER_ID, "42")
            MDC.put(OrderMdc.EVENT_ID, "event-uuid")
            MDC.put(OrderMdc.ZONE_ID, "7")

            try {
                val result = proxy.doAction()
                assertThat(result).isEqualTo("SUCCESS")
            } finally {
                MDC.clear()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 예외 재전파
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    inner class `예외 재전파` {
        @Test
        fun `proceed에서 예외 발생 시 호출부로 재전파된다`() {
            val proxy = proxyOf<FakeService>(FailingServiceImpl())

            assertThatThrownBy { proxy.doAction() }
                .isInstanceOf(TestProcessingException::class.java)
                .hasMessage("처리 실패")
        }

        @Test
        fun `BusinessException은 WARN 경로이지만 삼키지 않고 재전파된다`() {
            val proxy = proxyOf<FakeService>(BusinessFailingServiceImpl())

            assertThatThrownBy { proxy.doAction() }
                .isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `예외 발생 시 반환값이 null이 아닌 예외로 전파된다 (삼킴 방지)`() {
            val proxy = proxyOf<FakeService>(FailingServiceImpl())

            val result = runCatching { proxy.doAction() }

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(TestProcessingException::class.java)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 어노테이션 없는 메서드
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    inner class `어노테이션 없는 메서드` {
        @Test
        fun `@LogAction이 없는 메서드는 Aspect가 개입하지 않고 그대로 실행된다`() {
            val proxy = proxyOf<FakeService>(FakeServiceImpl())

            val result = proxy.doWithoutAnnotation()

            assertThat(result).isEqualTo("NO_ANNOTATION")
        }

        @Test
        fun `@LogAction이 없는 메서드에서 예외가 발생해도 Aspect가 관여하지 않는다`() {
            val proxy = proxyOf<FakeService>(NoAnnotationFailingServiceImpl())

            // Aspect 없이 예외가 그대로 전파되는지 확인
            assertThatThrownBy { proxy.doWithoutAnnotation() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("어노테이션 없는 메서드 실패")
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    // reified로 인터페이스 타입을 명시 — JDK 프록시는 구현 클래스로 캐스트 불가
    private inline fun <reified T : Any> proxyOf(target: Any): T {
        val factory = AspectJProxyFactory(target)
        factory.addAspect(aspect)
        return T::class.java.cast(factory.getProxy())
    }

    // ── 테스트 더블 ──────────────────────────────────────────────────────────────

    interface FakeService {
        fun doAction(): String

        fun doWithoutAnnotation(): String
    }

    /** 정상 케이스 */
    open class FakeServiceImpl : FakeService {
        @LogAction("TEST_ACTION")
        override fun doAction(): String = "SUCCESS"

        override fun doWithoutAnnotation(): String = "NO_ANNOTATION"
    }

    /** RuntimeException 케이스 */
    open class FailingServiceImpl : FakeService {
        @LogAction("TEST_ACTION")
        override fun doAction(): String = throw TestProcessingException("처리 실패")

        override fun doWithoutAnnotation(): String = "NO_ANNOTATION"
    }

    /** BusinessException 케이스 — Aspect logError 의 WARN 분기 검증 */
    open class BusinessFailingServiceImpl : FakeService {
        @LogAction("TEST_ACTION")
        override fun doAction(): String = throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)

        override fun doWithoutAnnotation(): String = "NO_ANNOTATION"
    }

    /**
     * 테스트 전용 비터미널 예외 — BusinessException 이 아닌 ERROR 경로 검증용.
     * RuntimeException 상속: JDK 동적 프록시는 인터페이스에 선언되지 않은
     * checked exception을 UndeclaredThrowableException으로 래핑하므로 unchecked로 정의한다.
     */
    class TestProcessingException(
        message: String,
    ) : RuntimeException(message)

    /** 어노테이션 없는 메서드 실패 케이스 */
    open class NoAnnotationFailingServiceImpl : FakeService {
        @LogAction("TEST_ACTION")
        override fun doAction(): String = "OK"

        override fun doWithoutAnnotation(): String = throw IllegalStateException("어노테이션 없는 메서드 실패")
    }
}
