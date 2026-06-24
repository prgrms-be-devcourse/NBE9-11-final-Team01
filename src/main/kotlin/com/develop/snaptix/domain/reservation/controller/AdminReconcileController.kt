package com.develop.snaptix.domain.reservation.controller

import com.develop.snaptix.domain.reservation.service.ReconcileReport
import com.develop.snaptix.domain.reservation.service.ReconcileService
import com.develop.snaptix.global.security.auth.CurrentUserProvider
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

/**
 * 수동 정산 3차 방어선. (작업 명세서 §6.4 · Story 13.3-3차 · 9.4)
 *
 * 스케줄러와 **완전히 동일한 [ReconcileService]** 를 호출한다(중복 로직 없음).
 * 차이는 트리거(수동)와 감사 로그 `actorId`(=인증 관리자 userId)뿐이다.
 */
@RestController
@RequestMapping("/api/v1/admin/reconcile")
class AdminReconcileController(
    private val reconcileService: ReconcileService,
    private val currentUserProvider: CurrentUserProvider,
    private val clock: Clock,
) {
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun reconcile(): ResponseEntity<ReconcileReport> {
        val actorId = currentUserProvider.getCurrentUserId()
        val report = reconcileService.reconcileExpired(Instant.now(clock))

        reconcileService.writeAdminAudit(actorId, report)
        return ResponseEntity.ok(report)
    }
}
