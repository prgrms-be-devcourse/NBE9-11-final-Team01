package com.develop.snaptix.domain.order.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "order.stream")
data class OrderStreamProperties(
    val consumerGroup: String = "order-workers",
)
