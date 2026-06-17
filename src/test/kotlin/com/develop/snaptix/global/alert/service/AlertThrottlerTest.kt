package com.develop.snaptix.global.alert.service

import com.develop.snaptix.global.alert.config.AlertProperties
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val CONCURRENT_ATTEMPTS = 20

class AlertThrottlerTest {
    @Test
    fun `동일 key 동시 진입 시 하나만 획득한다`() {
        val throttler = AlertThrottler(createProperties())
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

        readyLatch.await(1, TimeUnit.SECONDS)
        startLatch.countDown()
        doneLatch.await(1, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertThat(acquiredCount.get()).isEqualTo(1)
    }

    @Test
    fun `스로틀 윈도우가 0초이면 만료된 key를 다시 획득할 수 있다`() {
        val throttler = AlertThrottler(createProperties(windowSeconds = 0))
        val context =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            )

        assertThat(throttler.tryAcquire(context)).isTrue()
        assertThat(throttler.tryAcquire(context)).isTrue()
    }

    private fun createProperties(windowSeconds: Long = 300): AlertProperties =
        AlertProperties().apply {
            throttle.windowSeconds = windowSeconds
        }
}
