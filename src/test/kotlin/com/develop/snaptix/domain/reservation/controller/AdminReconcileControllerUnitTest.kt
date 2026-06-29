package com.develop.snaptix.domain.reservation.controller

import com.develop.snaptix.domain.reservation.service.ReconcileReport
import com.develop.snaptix.domain.reservation.service.ReconcileService
import com.develop.snaptix.global.observability.ReconcileMetrics
import com.develop.snaptix.global.security.auth.CurrentUserProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

class AdminReconcileControllerUnitTest {
    private val reconcileService = mockk<ReconcileService>(relaxed = true)
    private val currentUserProvider = mockk<CurrentUserProvider>()
    private val reconcileMetrics = mockk<ReconcileMetrics>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
    private val controller = AdminReconcileController(reconcileService, currentUserProvider, reconcileMetrics, clock)

    @Test
    fun `reconcile은 정산 결과를 200으로 반환하고 ADMIN 감사기록을 위임한다`() {
        val report = ReconcileReport(released = 1, compensated = 1, failed = 0)
        every { currentUserProvider.getCurrentUserId() } returns 1L
        every { reconcileService.reconcileExpired(any()) } returns report

        val response = controller.reconcile()

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.released).isEqualTo(1)
        verify { reconcileService.writeAdminAudit(1L, report) } // 위임 검증
    }
}
