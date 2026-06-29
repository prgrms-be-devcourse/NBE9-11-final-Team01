package com.develop.snaptix.domain.payment.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "payment.mock.webhook")
data class MockPaymentWebhookProperties(
    val secret: String,
)
