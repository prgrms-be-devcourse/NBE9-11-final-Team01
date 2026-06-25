package com.develop.snaptix.domain.reservation.scheduler

import com.develop.snaptix.domain.reservation.service.DriftReconciliationService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 상시 드리프트 정산 트리거. (작업 명세서 v2.1 §6 · Story 13.4 / S-13)
 *
 * `@Scheduled` 30분 주기(장애 무관)로 [DriftReconciliationService.checkDrift] 를 호출한다.
 * 진입 시 [clock] 으로 `now` 를 1회 고정(계약 #0: 시계 고정) 후 서비스에 전달한다.
 *
 * Clock 은 `ReconcileScheduler` 와 동일한 `alertClock` 빈을 공유한다(테스트 시 고정 Clock 주입 용이).
 * 빈 이름이 프로젝트에서 다르면 `@Qualifier` 값만 맞추면 된다.
 */
@Component
class DriftReconciliationScheduler(
    private val driftReconciliationService: DriftReconciliationService,
    @Qualifier("alertClock") private val clock: Clock,
) {
    @Scheduled(cron = "\${reconcile.drift-cron}")
    fun checkDrift() {
        driftReconciliationService.checkDrift(Instant.now(clock))
    }
}
