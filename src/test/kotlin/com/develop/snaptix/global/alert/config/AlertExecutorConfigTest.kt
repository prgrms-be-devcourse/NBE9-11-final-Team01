package com.develop.snaptix.global.alert.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

class AlertExecutorConfigTest {
    @Test
    fun `alertExecutor는 알림 전용 스레드풀과 거부 정책을 사용한다`() {
        val executor = AlertExecutorConfig().alertExecutor()

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor::class.java)
        val taskExecutor = executor as ThreadPoolTaskExecutor

        assertThat(taskExecutor.corePoolSize).isEqualTo(1)
        assertThat(taskExecutor.maxPoolSize).isEqualTo(2)
        assertThat(taskExecutor.threadNamePrefix).isEqualTo("alert-")
        assertThat(taskExecutor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(100)
        assertThat(taskExecutor.threadPoolExecutor.rejectedExecutionHandler)
            .isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy::class.java)

        taskExecutor.shutdown()
    }
}
