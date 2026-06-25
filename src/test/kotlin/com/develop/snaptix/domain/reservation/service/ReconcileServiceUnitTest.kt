package com.develop.snaptix.domain.reservation.service

import com.develop.snaptix.domain.auditlog.repository.AuditLogRepository
import com.develop.snaptix.domain.reservation.repository.ExpiredReservation
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ReconcileServiceUnitTest {
    private val reservationRepository = mockk<ReservationRepository>()
    private val stockGateway = mockk<StockRedisGateway>()
    private val auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
    private val properties = ReconcileProperties().apply { holdWindow = Duration.ofMinutes(5) }
    private val service = ReconcileService(reservationRepository, stockGateway, auditLogRepository, properties)

    private val now = Instant.parse("2026-06-22T00:00:00Z")
    private val orderId = UUID.randomUUID().toString()
    private val expired = ExpiredReservation(id = 1L, orderId = orderId, zoneId = 10L)

    // 실패해도 기록 남기고 진행하는 테스트용
    private val r1 = ExpiredReservation(id = 1L, orderId = UUID.randomUUID().toString(), zoneId = 10L)
    private val r2 = ExpiredReservation(id = 2L, orderId = UUID.randomUUID().toString(), zoneId = 20L)

    @Test
    fun `affected=1 이고 claimed 보유면 released·compensated 증가 + 올바른 인자로 호출`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(expired)
        every { reservationRepository.releaseIfPending(1L) } returns 1
        every { stockGateway.compensate(10L, UUID.fromString(orderId)) } returns true

        val report = service.reconcileExpired(now)

        Assertions.assertThat(report.released).isEqualTo(1)
        Assertions.assertThat(report.compensated).isEqualTo(1)
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

        Assertions.assertThat(report.released).isEqualTo(0)
        Assertions.assertThat(report.compensated).isEqualTo(0)
        verify(exactly = 0) { stockGateway.compensate(any(), any()) }
    }

    @Test
    fun `claimed에 없어 compensate=false면 released는 증가, compensated는 증가 안 함`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(expired)
        every { reservationRepository.releaseIfPending(1L) } returns 1
        every { stockGateway.compensate(any(), any()) } returns false

        val report = service.reconcileExpired(now)

        Assertions.assertThat(report.released).isEqualTo(1)
        Assertions.assertThat(report.compensated).isEqualTo(0)
    }

    @Test
    fun `만료 건이 없으면 아무 동작도 하지 않는다`() {
        every { reservationRepository.findExpiredPending(any()) } returns emptyList()

        val report = service.reconcileExpired(now)

        Assertions.assertThat(report.released).isEqualTo(0)
        Assertions.assertThat(report.compensated).isEqualTo(0)
        verify(exactly = 0) { reservationRepository.releaseIfPending(any()) }
        verify(exactly = 0) { stockGateway.compensate(any(), any()) }
    }

    @Test
    fun `한 건 보상 중 예외가 나도 다음 건은 계속 정산하고 failed로 집계한다`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(r1, r2)
        every { reservationRepository.releaseIfPending(any()) } returns 1
        every { stockGateway.compensate(r1.zoneId, UUID.fromString(r1.orderId)) } throws RuntimeException("redis down")
        every { stockGateway.compensate(r2.zoneId, UUID.fromString(r2.orderId)) } returns true

        val report = service.reconcileExpired(now)

        Assertions.assertThat(report.released).isEqualTo(2) // 둘 다 RELEASED
        Assertions.assertThat(report.compensated).isEqualTo(1) // r2만 보상
        Assertions.assertThat(report.failed).isEqualTo(1) // r1 격리
    }

    @Test
    fun `감사 로그 insert가 실패해도 보상 결과는 유지된다(best-effort)`() {
        every { reservationRepository.findExpiredPending(any()) } returns listOf(r1)
        every { reservationRepository.releaseIfPending(r1.id) } returns 1
        every { stockGateway.compensate(r1.zoneId, any()) } returns true
        every { auditLogRepository.insert(null, any(), any(), r1.id, any()) } throws RuntimeException("audit fail")

        val report = service.reconcileExpired(now)

        Assertions.assertThat(report.compensated).isEqualTo(1) // 감사 실패가 보상 카운트를 깨지 않음
        Assertions.assertThat(report.failed).isEqualTo(0)
    }

    @Test
    fun `writeAdminAudit는 감사 insert가 실패해도 예외를 전파하지 않는다(best-effort)`() {
        every {
            auditLogRepository.insert(1L, "ADMIN_RECONCILE", null, null, any())
        } throws RuntimeException("audit db down")

        // 예외가 전파되지 않으면 통과(아무 throw 없이 끝남)
        service.writeAdminAudit(actorId = 1L, report = ReconcileReport(released = 1, compensated = 1, failed = 0))

        verify { auditLogRepository.insert(1L, "ADMIN_RECONCILE", null, null, any()) }
    }
}
