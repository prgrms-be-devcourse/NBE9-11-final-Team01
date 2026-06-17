package com.develop.snaptix.global.alert.service

import com.develop.snaptix.global.alert.config.AlertProperties
import com.develop.snaptix.global.alert.model.AlertContext
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val CLEANUP_INTERVAL_SECONDS = 60L

@Component
class AlertThrottler(
    private val alertProperties: AlertProperties,
) {
    private val lastSentAtByKey = ConcurrentHashMap<String, Instant>()
    private val lastCleanupEpochSeconds = AtomicLong(0)

    fun tryAcquire(context: AlertContext): Boolean {
        val now = Instant.now()
        val key = context.throttleKey()
        val windowSeconds = alertProperties.throttle.windowSeconds
        val acquired = AtomicBoolean(false)

        cleanupExpiredKeys(now, windowSeconds)

        lastSentAtByKey.compute(key) { _, previous ->
            if (previous != null && previous.plusSeconds(windowSeconds).isAfter(now)) {
                previous
            } else {
                acquired.set(true)
                now
            }
        }

        return acquired.get()
    }

    private fun cleanupExpiredKeys(
        now: Instant,
        windowSeconds: Long,
    ) {
        val currentEpochSeconds = now.epochSecond
        val previousCleanupEpochSeconds = lastCleanupEpochSeconds.get()

        if (currentEpochSeconds - previousCleanupEpochSeconds < CLEANUP_INTERVAL_SECONDS) {
            return
        }

        if (!lastCleanupEpochSeconds.compareAndSet(previousCleanupEpochSeconds, currentEpochSeconds)) {
            return
        }

        lastSentAtByKey.entries.removeIf { (_, lastSentAt) ->
            !lastSentAt.plusSeconds(windowSeconds).isAfter(now)
        }
    }

    private fun AlertContext.throttleKey(): String =
        listOfNotNull(
            trigger.name,
            eventId,
            zoneId,
            fields["circuitName"]?.toString(),
        ).joinToString(":")
}
