package com.develop.snaptix.domain.reservation.scheduler

import com.develop.snaptix.domain.reservation.service.ReconcileService
import com.develop.snaptix.global.observability.ReconcileMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
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
    private val reconcileMetrics: ReconcileMetrics,
    @Qualifier("alertClock") private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    @Scheduled(cron = "\${reconcile.scheduler-cron}") // 기본값 포함
    fun reconcile() {
        try {
            // reconcile() try 블록 — 기존 "val report = ..." 줄을 이 3줄로 교체
            val start = System.nanoTime()
            val report = reconcileService.reconcileExpired(Instant.now(clock))
            reconcileMetrics.record(
                report,
                System.nanoTime() - start,
                ReconcileMetrics.Trigger.SCHEDULED,
            )

            logger.atInfo {
                message = "Scheduled reconcile finished"
                payload =
                    mapOf(
                        "released" to report.released,
                        "compensated" to report.compensated,
                        "failed" to report.failed,
                    )
            }
        } catch (e: Exception) {
            logger.atError {
                message = "Scheduled reconcile failed unexpectedly"
                cause = e
                payload = mapOf("clock" to clock.instant().toString())
            }
        }
    }
}
