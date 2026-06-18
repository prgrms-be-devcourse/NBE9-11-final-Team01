package com.develop.snaptix.global.alert.service

import com.develop.snaptix.global.alert.config.AlertProperties
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val CONCURRENT_ATTEMPTS = 20
private const val DEFAULT_WINDOW_SECONDS = 300L
private const val CLEANUP_GATE_SECONDS = 60L

class AlertThrottlerTest {
    @Test
    fun `동일 key 동시 진입 시 하나만 획득한다`() {
        val throttler = AlertThrottler(createProperties(), Clock.systemUTC())
        val executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)
        val readyLatch = CountDownLatch(CONCURRENT_ATTEMPTS)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(CONCURRENT_ATTEMPTS)
        val acquiredCount = AtomicInteger(0)
        val context =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            )

        repeat(CONCURRENT_ATTEMPTS) {
            executor.execute {
                readyLatch.countDown()
                startLatch.await()
                if (throttler.tryAcquire(context)) {
                    acquiredCount.incrementAndGet()
                }
                doneLatch.countDown()
            }
        }

        assertThat(readyLatch.await(1, TimeUnit.SECONDS)).isTrue()
        startLatch.countDown()
        assertThat(doneLatch.await(1, TimeUnit.SECONDS)).isTrue()
        executor.shutdownNow()

        assertThat(acquiredCount.get()).isEqualTo(1)
    }

    @Test
    fun `동일 key는 스로틀 윈도우 안에서 두 번째 획득을 거부한다`() {
        val clock = MutableClock()
        val throttler = AlertThrottler(createProperties(), clock)
        val context =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            )

        assertThat(throttler.tryAcquire(context)).isTrue()
        assertThat(throttler.tryAcquire(context)).isFalse()
    }

    @Test
    fun `스로틀 윈도우가 만료되면 동일 key를 다시 획득할 수 있다`() {
        val clock = MutableClock()
        val throttler = AlertThrottler(createProperties(), clock)
        val context =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            )

        assertThat(throttler.tryAcquire(context)).isTrue()

        clock.advanceSeconds(DEFAULT_WINDOW_SECONDS + 1)

        assertThat(throttler.tryAcquire(context)).isTrue()
    }

    @Test
    fun `서로 다른 key는 독립적으로 획득할 수 있다`() {
        val clock = MutableClock()
        val throttler = AlertThrottler(createProperties(), clock)
        val redisContext =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            )
        val paymentRedisContext =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "payment-redis"),
            )

        assertThat(throttler.tryAcquire(redisContext)).isTrue()
        assertThat(throttler.tryAcquire(paymentRedisContext)).isTrue()
    }

    @Test
    fun `cleanup 주기가 지나면 만료된 key를 제거한다`() {
        val clock = MutableClock()
        val throttler = AlertThrottler(createProperties(), clock)
        val expiredContext =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "expired-redis"),
            )
        val currentContext =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "current-redis"),
            )

        assertThat(throttler.tryAcquire(expiredContext)).isTrue()
        assertThat(throttler.activeThrottleKeyCount()).isEqualTo(1)

        clock.advanceSeconds(DEFAULT_WINDOW_SECONDS + CLEANUP_GATE_SECONDS + 1)

        assertThat(throttler.tryAcquire(currentContext)).isTrue()
        assertThat(throttler.activeThrottleKeyCount()).isEqualTo(1)
    }

    private fun createProperties(windowSeconds: Long = DEFAULT_WINDOW_SECONDS): AlertProperties =
        AlertProperties().apply {
            throttle.windowSeconds = windowSeconds
        }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-06-18T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
