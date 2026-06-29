package com.develop.snaptix.global.alert.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "alert")
class AlertProperties {
    var dashboardBaseUrl: String? = null
    val slack: Slack = Slack()
    val throttle: Throttle = Throttle()

    class Slack {
        var enabled: Boolean = false
        var webhookUrl: String? = null
        var channel: String? = null
        var mentionOnCritical: Boolean = false
    }

    class Throttle {
        var windowSeconds: Long = 300
    }
}
