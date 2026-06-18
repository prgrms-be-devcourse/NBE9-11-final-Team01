package com.develop.snaptix.global.alert.service

import com.develop.snaptix.global.alert.config.AlertProperties
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.Executor

private const val WEBHOOK_URL = "https://hooks.slack.test/services/test"

class SlackAlertServiceTest {
    private val slackWebhookClient = mockk<SlackWebhookClient>()
    private val directExecutor = Executor { command -> command.run() }

    @Test
    fun `Slack 알림이 비활성화되어 있으면 Webhook을 호출하지 않는다`() {
        val service = createService(slackEnabled = false)

        service.notify(
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            ),
        )

        verify(exactly = 0) { slackWebhookClient.send(any(), any()) }
    }

    @Test
    fun `Slack 알림이 활성화되어 있으면 Webhook으로 payload를 전송한다`() {
        every { slackWebhookClient.send(any(), any()) } just runs
        val payloadSlot = slot<Map<String, Any?>>()
        val service = createService()

        service.notify(
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                traceId = "trace-1",
                fields = mapOf("circuitName" to "redis"),
            ),
        )

        verify(exactly = 1) {
            slackWebhookClient.send(WEBHOOK_URL, capture(payloadSlot))
        }
        assertThat(payloadSlot.captured["text"].toString())
            .contains("[CRITICAL]")
            .contains("Redis 서킷 OPEN")
    }

    @Test
    fun `동일 알림은 스로틀 윈도우 안에서 한 번만 전송한다`() {
        every { slackWebhookClient.send(any(), any()) } just runs
        val service = createService()
        val context =
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            )

        service.notify(context)
        service.notify(context)

        verify(exactly = 1) { slackWebhookClient.send(any(), any()) }
    }

    @Test
    fun `Slack Webhook 호출이 실패해도 예외를 전파하지 않는다`() {
        every { slackWebhookClient.send(any(), any()) } throws RuntimeException("slack down")
        val service = createService()

        service.notify(
            AlertContext(
                trigger = AlertTrigger.CIRCUIT_OPEN,
                fields = mapOf("circuitName" to "redis"),
            ),
        )

        verify(exactly = 1) { slackWebhookClient.send(any(), any()) }
    }

    private fun createService(slackEnabled: Boolean = true): SlackAlertService {
        val properties =
            AlertProperties().apply {
                slack.enabled = slackEnabled
                slack.webhookUrl = WEBHOOK_URL
                slack.channel = "#ticketing-redis-alerts"
                slack.mentionOnCritical = false
                throttle.windowSeconds = 300
            }

        return SlackAlertService(
            slackWebhookClient = slackWebhookClient,
            alertThrottler = AlertThrottler(properties, Clock.systemUTC()),
            alertProperties = properties,
            alertExecutor = directExecutor,
        )
    }
}
