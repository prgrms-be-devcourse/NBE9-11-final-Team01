package com.develop.snaptix.global.redis.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * RedisTtlProperties 바인딩 테스트.
 *
 * Spring 컨텍스트 전체나 Redis 없이 [ApplicationContextRunner]로 프로퍼티 바인딩만 검증한다.
 */
class RedisTtlPropertiesTest {
    @EnableConfigurationProperties(RedisTtlProperties::class)
    private class TestConfig

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `기본값이 Redis 키 명세서 v3_1 정책과 일치한다`() {
        contextRunner.run { context ->
            val props = context.getBean(RedisTtlProperties::class.java)

            assertThat(props.ingestEnvelope).isEqualTo(Duration.ofMinutes(8))
            assertThat(props.orderHold).isEqualTo(Duration.ofMinutes(5))
            assertThat(props.webhookProcessed).isEqualTo(Duration.ofMinutes(10))
            assertThat(props.paymentApprove).isEqualTo(Duration.ofSeconds(60))
            assertThat(props.eventInfo).isEqualTo(Duration.ofHours(1))
            assertThat(props.orderPending).isEqualTo(Duration.ofMinutes(2))
            assertThat(props.rateLimitSecond).isEqualTo(Duration.ofSeconds(1))
            assertThat(props.rateLimitMinute).isEqualTo(Duration.ofSeconds(60))
        }
    }

    @Test
    fun `application 속성으로 TTL을 오버라이드할 수 있다`() {
        contextRunner
            .withPropertyValues(
                "snaptix.redis.ttl.ingest-envelope=10m",
                "snaptix.redis.ttl.order-hold=3m",
            ).run { context ->
                val props = context.getBean(RedisTtlProperties::class.java)

                assertThat(props.ingestEnvelope).isEqualTo(Duration.ofMinutes(10))
                assertThat(props.orderHold).isEqualTo(Duration.ofMinutes(3))
                // 미지정 값은 기본값을 유지한다.
                assertThat(props.eventInfo).isEqualTo(Duration.ofHours(1))
            }
    }
}
