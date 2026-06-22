package com.develop.snaptix.global.realtime

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/**
 * SSE 전송 전용 스레드 풀 (PR-06).
 *
 * dispatch 의 emitter.send 를 이 풀에서 실행해 Redis 리스너/요청 스레드를 블로킹하지 않는다.
 * 거절 정책은 CallerRunsPolicy — 과부하 시 호출 스레드에서 실행해 역압(backpressure)을 건다.
 *
 * 풀 설정은 `realtime.sse.send.*`로 외부화(기본값 내장). 문자열 기본값이라 MagicNumber 미해당.
 */
@Configuration
class SseExecutorConfig {
    @Bean(SSE_SEND_EXECUTOR)
    fun sseSendExecutor(
        @Value("\${realtime.sse.send.core-pool-size:4}") corePoolSize: Int,
        @Value("\${realtime.sse.send.max-pool-size:16}") maxPoolSize: Int,
        @Value("\${realtime.sse.send.queue-capacity:1000}") queueCapacity: Int,
    ): Executor =
        ThreadPoolTaskExecutor().apply {
            this.corePoolSize = corePoolSize
            this.maxPoolSize = maxPoolSize
            this.queueCapacity = queueCapacity
            setThreadNamePrefix("sse-send-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            initialize()
        }

    companion object {
        const val SSE_SEND_EXECUTOR = "sseSendExecutor"
    }
}
