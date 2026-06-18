package com.develop.snaptix.global.alert.model

enum class AlertSeverity(
    val icon: String,
) {
    CRITICAL(":rotating_light:"),
    WARN(":warning:"),
    INFO(":information_source:"),
}
