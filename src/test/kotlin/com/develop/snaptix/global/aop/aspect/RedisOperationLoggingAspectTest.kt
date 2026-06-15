package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.aop.annotation.RedisOperation
import com.develop.snaptix.global.aop.type.RedisAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory

class RedisOperationLoggingAspectTest {
    private lateinit var proxy: TestRedisService

    @BeforeEach
    fun setUp() {
        MDC.put("traceId", "test-trace-id-001")

        val factory = AspectJProxyFactory(TestRedisService())
        factory.addAspect(RedisOperationLoggingAspect())
        proxy = factory.getProxy()
    }

    @Test
    fun `성공 시 예외 없이 정상 반환한다`() {
        val result = proxy.successOperation()
        assertThat(result).isEqualTo(10L)
    }

    @Test
    fun `실패 시 예외를 삼키지 않고 re-throw한다`() {
        assertThrows<IllegalStateException> {
            proxy.failOperation()
        }
    }

    @Test
    fun `어노테이션이 없는 메서드는 Aspect가 개입하지 않는다`() {
        val result = proxy.noAnnotationOperation()
        assertThat(result).isEqualTo("no-aspect")
    }

    // 테스트용 서비스 (inner class)
    open class TestRedisService {
        @RedisOperation(action = RedisAction.LUASCRIPT_DECREASE)
        open fun successOperation(): Long = 10L

        @RedisOperation(action = RedisAction.QUEUE_PUSH)
        open fun failOperation(): Unit = throw IllegalStateException("Redis 연결 실패")

        open fun noAnnotationOperation(): String = "no-aspect"
    }
}
