package com.develop.snaptix.global.alert.model

data class AlertContext(
    val trigger: AlertTrigger,
    val eventId: String? = null,
    val zoneId: String? = null,
    val traceId: String? = null,
    val fields: Map<String, Any?> = emptyMap(),
)
