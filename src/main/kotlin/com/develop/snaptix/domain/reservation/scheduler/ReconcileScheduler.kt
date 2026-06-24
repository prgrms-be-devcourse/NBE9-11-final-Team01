package com.develop.snaptix.domain.reservation.scheduler

import com.develop.snaptix.domain.reservation.service.ReconcileService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 만료 정산 2차 방어선. (작업 명세서 §11-6 · Story 13.3-2차)
 * `@Scheduled` 10분 주기로 [ReconcileService]를 호출한다(정답 소스 = 배치).
 */
@Component
class ReconcileScheduler(
    private val reconcileService: ReconcileService,
    private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(cron = "\${reconcile.scheduler-cron}")
    fun reconcile() {
        val report = reconcileService.reconcileExpired(Instant.now(clock))
        logger.atInfo {
            message = "Scheduled reconcile finished"
            payload =
                mapOf(
                    "released" to report.released,
                    "compensated" to report.compensated,
                    "failed" to report.failed,
                )
        }
    }
}
