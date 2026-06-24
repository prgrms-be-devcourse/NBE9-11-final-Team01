package com.develop.snaptix.global.redis.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.script.RedisScript

/**
 * RedisScriptConfig 빈 등록 검증.
 *
 * 스크립트가 올바른 결과 타입과 텍스트로 등록되는지 확인한다. (Redis 불필요)
 * 스크립트의 실제 동작(차감/보상/CAD) 검증은 Testcontainers를 쓰는 ISSUE-05·07에서 수행한다.
 */
class RedisScriptConfigTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(RedisScriptConfig::class.java)

    @Test
    fun `decreaseAndClaim 스크립트가 String 결과 타입으로 등록된다`() {
        contextRunner.run { context ->
            val script = context.getBean("decreaseAndClaimScript", RedisScript::class.java)

            assertThat(script.resultType).isEqualTo(String::class.java)
            assertThat(script.scriptAsString).contains("ALREADY", "SOLD_OUT", "SADD")
        }
    }

    @Test
    fun `compensateStock 스크립트가 Long 결과 타입으로 등록된다`() {
        contextRunner.run { context ->
            val script = context.getBean("compensateStockScript", RedisScript::class.java)

            assertThat(script.resultType).isEqualTo(Long::class.java)
            assertThat(script.scriptAsString).contains("SISMEMBER", "INCR", "SREM")
        }
    }

    @Test
    fun `compareAndDelete 스크립트가 Long 결과 타입으로 등록된다`() {
        contextRunner.run { context ->
            val script = context.getBean("compareAndDeleteScript", RedisScript::class.java)

            assertThat(script.resultType).isEqualTo(Long::class.java)
            assertThat(script.scriptAsString).contains("GET", "DEL")
        }
    }

    @Test
    fun `동일 타입 RedisScript_Long 빈은 이름으로 구분되어 모두 등록된다`() {
        contextRunner.run { context ->
            assertThat(context.getBeanNamesForType(RedisScript::class.java))
                .contains("decreaseAndClaimScript", "compensateStockScript", "compareAndDeleteScript")
        }
    }
}
