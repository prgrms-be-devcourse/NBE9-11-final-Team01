package com.develop.snaptix.global.alert.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AlertClockConfig {
    @Bean
    fun alertClock(): Clock = Clock.systemUTC()
}
