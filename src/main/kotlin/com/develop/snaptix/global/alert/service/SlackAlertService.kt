package com.develop.snaptix.global.alert.service

import com.develop.snaptix.global.alert.config.AlertProperties
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertSeverity
import com.develop.snaptix.global.alert.model.AlertTrigger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor

private val KST_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")

@Service
class SlackAlertService(
    private val slackWebhookClient: SlackWebhookClient,
    private val alertThrottler: AlertThrottler,
    private val alertProperties: AlertProperties,
    @Qualifier("alertExecutor") private val alertExecutor: Executor,
) : AlertService {
    private val logger = KotlinLogging.logger {}

    override fun notify(context: AlertContext) {
        if (!alertProperties.slack.enabled) {
            return
        }

        val webhookUrl = alertProperties.slack.webhookUrl?.takeIf { it.isNotBlank() } ?: return

        alertExecutor.execute {
            runCatching {
                if (!alertThrottler.tryAcquire(context)) {
                    logger.atInfo {
                        message = "Slack alert throttled"
                        payload = mapOf("trigger" to context.trigger.name)
                    }
                    return@execute
                }

                slackWebhookClient.send(
                    webhookUrl = webhookUrl,
                    payload = buildPayload(context),
                )
            }.onFailure { exception ->
                logger.atWarn {
                    message = "Slack alert dispatch failed"
                    cause = exception
                    payload = mapOf("trigger" to context.trigger.name)
                }
            }
        }
    }

    private fun buildPayload(context: AlertContext): Map<String, Any?> {
        val headline = context.headline()
        val text =
            if (context.trigger.severity == AlertSeverity.CRITICAL && alertProperties.slack.mentionOnCritical) {
                "<!here> $headline"
            } else {
                headline
            }

        return mapOf(
            "channel" to alertProperties.slack.channel,
            "text" to text,
            "blocks" to
                listOf(
                    mapOf(
                        "type" to "header",
                        "text" to
                            mapOf(
                                "type" to "plain_text",
                                "text" to headline,
                            ),
                    ),
                    mapOf(
                        "type" to "section",
                        "fields" to context.fields(),
                    ),
                ),
        )
    }

    private fun AlertContext.headline(): String = "${trigger.severity.icon} ${trigger.label()}"

    private fun AlertTrigger.label(): String = "[${severity.name}] $summary"

    private fun AlertContext.fields(): List<Map<String, String>> {
        val timeKst =
            OffsetDateTime
                .now(KST_ZONE_ID)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val baseFields =
            listOfNotNull(
                mrkdwnField("Trigger", trigger.name),
                mrkdwnField("Event", eventId),
                mrkdwnField("Zone", zoneId),
                mrkdwnField("Trace", traceId),
                mrkdwnField("Time(KST)", timeKst),
            )

        val extraFields =
            fields
                .map { (key, value) ->
                    mrkdwnField(key, value?.toString() ?: "null")
                }.filterNotNull()

        return baseFields + extraFields
    }

    private fun mrkdwnField(
        label: String,
        value: String?,
    ): Map<String, String>? = value?.let {
        mapOf(
            "type" to "mrkdwn",
            "text" to "*$label:*\n$it",
        )
    }
}
