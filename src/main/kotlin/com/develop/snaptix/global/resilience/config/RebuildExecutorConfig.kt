package com.develop.snaptix.global.resilience.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

private const val REBUILD_EXECUTOR_CORE_POOL_SIZE = 1
private const val REBUILD_EXECUTOR_MAX_POOL_SIZE = 1

// queueCapacity = 0 → SynchronousQueue: 스레드가 바쁘면 신규 트리거는 즉시 거부(DiscardPolicy)되어
// 중복 재구축이 큐에 쌓여 재실행되는 일을 막는다(실제 단일 실행 보장은 코디네이터 락).
private const val REBUILD_EXECUTOR_QUEUE_CAPACITY = 0

/**
 * 재구축 전용 단일 스레드 executor. (작업 명세서 v2.1 §8)
 *
 * 서킷 리스너(#9) 스레드 블로킹을 회피한다. 중복 트리거는 DiscardPolicy 로 흘리고,
 * 실제 단일 실행 보장은 [com.develop.snaptix.global.resilience.RebuildCoordinator] 의 Redis 락이 담당한다.
 */
@Configuration
class RebuildExecutorConfig {
    @Bean("rebuildExecutor")
    fun rebuildExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = REBUILD_EXECUTOR_CORE_POOL_SIZE
        maxPoolSize = REBUILD_EXECUTOR_MAX_POOL_SIZE
        queueCapacity = REBUILD_EXECUTOR_QUEUE_CAPACITY
        setThreadNamePrefix("rebuild-")
        setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy())
        initialize()
    }
}
