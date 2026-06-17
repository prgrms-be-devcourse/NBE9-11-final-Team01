package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import com.develop.snaptix.global.alert.service.AlertService
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisCircuitBreakerEventListenerTest {
    private val alertService = mockk<AlertService>()

    @Test
    fun `Redis circuit breaker가 OPEN으로 전이되면 Slack 알림을 요청한다`() {
        every { alertService.notify(any()) } returns Unit
        val registry = CircuitBreakerRegistry.ofDefaults()
        val circuitBreaker = registry.circuitBreaker("redis", CircuitBreakerConfig.custom().build())
        val alertContextSlot = slot<AlertContext>()

        RedisCircuitBreakerEventListener(
            circuitBreakerRegistry = registry,
            alertService = alertService,
        ).registerListeners()

        circuitBreaker.transitionToOpenState()

        verify(exactly = 1) { alertService.notify(capture(alertContextSlot)) }
        assertThat(alertContextSlot.captured.trigger).isEqualTo(AlertTrigger.CIRCUIT_OPEN)
        assertThat(alertContextSlot.captured.fields["circuitName"]).isEqualTo("redis")
        assertThat(alertContextSlot.captured.fields["to"]).isEqualTo("OPEN")
    }

    @Test
    fun `Redis circuit breaker가 OPEN이 아닌 상태로 전이되면 Slack 알림을 요청하지 않는다`() {
        val registry = CircuitBreakerRegistry.ofDefaults()
        val circuitBreaker = registry.circuitBreaker("redis", CircuitBreakerConfig.custom().build())

        RedisCircuitBreakerEventListener(
            circuitBreakerRegistry = registry,
            alertService = alertService,
        ).registerListeners()

        circuitBreaker.transitionToForcedOpenState()
        circuitBreaker.transitionToClosedState()

        verify(exactly = 0) { alertService.notify(any()) }
    }

    @Test
    fun `HALF_OPEN에서 OPEN으로 다시 전이되면 Slack 알림을 다시 요청한다`() {
        every { alertService.notify(any()) } returns Unit
        val registry = CircuitBreakerRegistry.ofDefaults()
        val circuitBreaker = registry.circuitBreaker("redis", CircuitBreakerConfig.custom().build())

        RedisCircuitBreakerEventListener(
            circuitBreakerRegistry = registry,
            alertService = alertService,
        ).registerListeners()

        circuitBreaker.transitionToOpenState()
        circuitBreaker.transitionToHalfOpenState()
        circuitBreaker.transitionToOpenState()

        verify(exactly = 2) { alertService.notify(any()) }
    }
}
