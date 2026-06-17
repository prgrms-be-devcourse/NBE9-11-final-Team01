package com.develop.snaptix.global.alert.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

private const val ALERT_EXECUTOR_CORE_POOL_SIZE = 1
private const val ALERT_EXECUTOR_MAX_POOL_SIZE = 2
private const val ALERT_EXECUTOR_QUEUE_CAPACITY = 100

@Configuration
class AlertExecutorConfig {
    @Bean("alertExecutor")
    fun alertExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = ALERT_EXECUTOR_CORE_POOL_SIZE
            maxPoolSize = ALERT_EXECUTOR_MAX_POOL_SIZE
            queueCapacity = ALERT_EXECUTOR_QUEUE_CAPACITY
            setThreadNamePrefix("alert-")
            setRejectedExecutionHandler(ThreadPoolExecutor.DiscardOldestPolicy())
            initialize()
        }
}
