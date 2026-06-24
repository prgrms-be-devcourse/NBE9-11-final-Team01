package com.develop.snaptix.domain.reservation.reconcile

import com.develop.snaptix.domain.auditlog.repository.AuditLogRepository
import com.develop.snaptix.domain.reservation.repository.ExpiredReservation
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.domain.reservation.service.ReconcileService
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class ReconcileServiceUnitTest {
    private val reservationRepository = mockk<ReservationRepository>()
    private val stockGateway = mockk<StockRedisGateway>()
    private val auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
    private val properties = ReconcileProperties().apply { holdWindow = Duration.ofMinutes(5) }
    private val service = ReconcileService(reservationRepository, stockGateway, auditLogRepository, properties)

    private val now = Instant.parse("2026-06-22T00:00:00Z")
    private val orderId = UUID.randomUUID().toString()
    private val expired = ExpiredReservation(id = 1L, orderId = orderId, zoneId = 10L)

    @Test
    fun `affected=1 이고 claimed 보유면 released·compensated 증가 + 올바른 인자로 호출`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(expired)
        every { reservationRepository.releaseIfPending(1L) } returns 1
        every { stockGateway.compensate(10L, UUID.fromString(orderId)) } returns true

        val report = service.reconcileExpired(now)

        assertThat(report.released).isEqualTo(1)
        assertThat(report.compensated).isEqualTo(1)
        // cutoff 방향 검증 (now - holdWindow)
        verify { reservationRepository.findExpiredPending(now.minus(Duration.ofMinutes(5))) }
        // 올바른 zoneId·orderId(UUID 변환)로 보상 호출
        verify { stockGateway.compensate(10L, UUID.fromString(orderId)) }
        // 감사 로그
        verify {
            auditLogRepository.insert(null, RedisAction.RECONCILE_RUN.name, "RESERVATION", 1L, any())
        }
    }

    @Test
    fun `affected=0 이면 건너뛴다 - compensate 미호출, released 0`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(expired)
        every { reservationRepository.releaseIfPending(1L) } returns 0

        val report = service.reconcileExpired(now)

        assertThat(report.released).isEqualTo(0)
        assertThat(report.compensated).isEqualTo(0)
        verify(exactly = 0) { stockGateway.compensate(any(), any()) }
    }

    @Test
    fun `claimed에 없어 compensate=false면 released는 증가, compensated는 증가 안 함`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(expired)
        every { reservationRepository.releaseIfPending(1L) } returns 1
        every { stockGateway.compensate(any(), any()) } returns false

        val report = service.reconcileExpired(now)

        assertThat(report.released).isEqualTo(1)
        assertThat(report.compensated).isEqualTo(0)
    }

    @Test
    fun `만료 건이 없으면 아무 동작도 하지 않는다`() {
        every { reservationRepository.findExpiredPending(any()) } returns emptyList()

        val report = service.reconcileExpired(now)

        assertThat(report.released).isEqualTo(0)
        assertThat(report.compensated).isEqualTo(0)
        verify(exactly = 0) { reservationRepository.releaseIfPending(any()) }
        verify(exactly = 0) { stockGateway.compensate(any(), any()) }
    }
}
