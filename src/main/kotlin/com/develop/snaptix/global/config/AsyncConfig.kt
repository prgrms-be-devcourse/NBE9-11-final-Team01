package com.develop.snaptix.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfig {
    @Bean("workerTaskExecutor")
    fun workerTaskExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = WORKER_QUEUE_CAPACITY // [Fix] 매직 넘버 상수화
        setThreadNamePrefix("order-worker-")
        initialize()
    }

    companion object {
        private const val WORKER_QUEUE_CAPACITY = 10
    }
}
