package com.develop.snaptix.global.alert.service

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val SLACK_CONNECT_TIMEOUT_SECONDS = 1L
private const val SLACK_REQUEST_TIMEOUT_SECONDS = 3L
private const val HTTP_BAD_REQUEST_STATUS = 400

interface SlackWebhookClient {
    fun send(
        webhookUrl: String,
        payload: Map<String, Any?>,
    )
}

@Component
class JdkSlackWebhookClient(
    private val objectMapper: ObjectMapper,
) : SlackWebhookClient {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(SLACK_CONNECT_TIMEOUT_SECONDS))
            .build()

    override fun send(
        webhookUrl: String,
        payload: Map<String, Any?>,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(SLACK_REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() < HTTP_BAD_REQUEST_STATUS) {
            "Slack webhook request failed. status=${response.statusCode()}"
        }
    }
}
