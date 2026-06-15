package com.develop.snaptix.global.security.config

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

private const val ADMIN_RESPONSE = "admin"
private const val STAFF_RESPONSE = "staff"
private const val EVENT_RESPONSE = "event"
private const val EVENT_ZONE_RESPONSE = "event-zone"
private const val HEALTH_RESPONSE = "health"

@RestController
class SecurityTestController {
    @GetMapping("/api/v1/admin/test")
    fun adminTest(): String = ADMIN_RESPONSE

    @GetMapping("/api/v1/staff/test")
    fun staffTest(): String = STAFF_RESPONSE

    @GetMapping("/api/v1/events/test")
    fun publicEventTest(): String = EVENT_RESPONSE

    @GetMapping("/api/v1/events/test/zones")
    fun publicEventZoneTest(): String = EVENT_ZONE_RESPONSE

    @GetMapping("/actuator/health")
    fun healthTest(): String = HEALTH_RESPONSE
}
