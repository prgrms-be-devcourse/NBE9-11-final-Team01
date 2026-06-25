package com.develop.snaptix.domain.reservation.service

import com.develop.snaptix.domain.auditlog.repository.AuditLogRepository
import com.develop.snaptix.domain.reservation.repository.DriftStockRepository
import com.develop.snaptix.domain.reservation.repository.ZoneExpected
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import com.develop.snaptix.global.alert.service.AlertService
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * DriftReconciliationService 단위 테스트 (MockK).
 *
 * 집계는 [DriftStockRepository]로 분리되어 서비스에 `transaction{}`이 없으므로 순수 단위 테스트가 가능하다.
 * 분기(누수/오버셀/동일/키부재)·DriftReport 집계·예외 격리(zone/청크)를 검증한다.
 */
class DriftReconciliationServiceUnitTest {
    private val driftStockRepository = mockk<DriftStockRepository>()
    private val stockGateway = mockk<StockRedisGateway>(relaxUnitFun = true) // correctStock: Unit
    private val auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
    private val alertService = mockk<AlertService>(relaxUnitFun = true) // notify: Unit
    private val properties = ReconcileProperties().apply { holdWindow = Duration.ofMinutes(5) }

    private val service =
        DriftReconciliationService(
            driftStockRepository,
            stockGateway,
            auditLogRepository,
            alertService,
            properties,
        )

    private val now = Instant.parse("2026-06-25T03:00:00Z")

    @Test
    fun `should_correctStock_보정과_STOCK_DRIFT_FIX_감사_when_누수가_감지되면`() {
        // given: expected 100, actual 95 (누수)
        every { driftStockRepository.aggregateExpectedStock(any()) } returns listOf(ZoneExpected(10L, 100))
        every { stockGateway.getAll(listOf(10L)) } returns mapOf(10L to 95)

        // when
        val report = service.checkDrift(now)

        // then
        verify(exactly = 1) { stockGateway.correctStock(10L, 100) } // expected 로 절대 SET
        verify(exactly = 1) {
            auditLogRepository.insert(null, RedisAction.STOCK_DRIFT_FIX.name, "ZONE", 10L, any())
        }
        verify(exactly = 0) { alertService.notify(any()) }
        assertThat(report.fixed).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
    }

    @Test
    fun `should_알림만_보내고_correctStock과_감사_미호출_when_오버셀이_감지되면`() {
        // given: expected 100, actual 105 (오버셀)
        every { driftStockRepository.aggregateExpectedStock(any()) } returns listOf(ZoneExpected(10L, 100))
        every { stockGateway.getAll(listOf(10L)) } returns mapOf(10L to 105)

        // when
        val report = service.checkDrift(now)

        // then
        verify(exactly = 1) {
            alertService.notify(match<AlertContext> { it.trigger == AlertTrigger.STOCK_DRIFT_OVERSELL })
        }
        verify(exactly = 0) { stockGateway.correctStock(any(), any()) } // 값 불변
        verify(exactly = 0) { auditLogRepository.insert(any(), any(), any(), any(), any()) } // 감사 없음
        assertThat(report.oversell).isEqualTo(1)
    }

    @Test
    fun `should_무동작_when_실제와_기대가_동일하면`() {
        every { driftStockRepository.aggregateExpectedStock(any()) } returns listOf(ZoneExpected(10L, 100))
        every { stockGateway.getAll(listOf(10L)) } returns mapOf(10L to 100)

        val report = service.checkDrift(now)

        verify(exactly = 0) { stockGateway.correctStock(any(), any()) }
        verify(exactly = 0) { alertService.notify(any()) }
        verify(exactly = 0) { auditLogRepository.insert(any(), any(), any(), any(), any()) }
        assertThat(report.unchanged).isEqualTo(1)
    }

    @Test
    fun `should_skip_when_stock_키가_없으면`() {
        // given: getAll 결과에 해당 zone 값이 null (키 부재)
        every { driftStockRepository.aggregateExpectedStock(any()) } returns listOf(ZoneExpected(10L, 100))
        every { stockGateway.getAll(listOf(10L)) } returns mapOf(10L to null)

        val report = service.checkDrift(now)

        verify(exactly = 0) { stockGateway.correctStock(any(), any()) }
        verify(exactly = 0) { alertService.notify(any()) }
        assertThat(report.skipped).isEqualTo(1)
    }

    @Test
    fun `should_holdCutoff를_now에서_holdWindow_뺀_값으로_집계호출_when_checkDrift하면`() {
        every { driftStockRepository.aggregateExpectedStock(any()) } returns emptyList()

        service.checkDrift(now)

        verify(exactly = 1) { driftStockRepository.aggregateExpectedStock(now.minus(Duration.ofMinutes(5))) }
    }

    @Test
    fun `should_보정결과_유지_when_감사_insert가_실패해도_best_effort면`() {
        every { driftStockRepository.aggregateExpectedStock(any()) } returns listOf(ZoneExpected(10L, 100))
        every { stockGateway.getAll(listOf(10L)) } returns mapOf(10L to 90)
        every {
            auditLogRepository.insert(null, RedisAction.STOCK_DRIFT_FIX.name, "ZONE", 10L, any())
        } throws RuntimeException("audit db down")

        val report = service.checkDrift(now)

        verify(exactly = 1) { stockGateway.correctStock(10L, 100) } // 보정은 수행됨
        assertThat(report.fixed).isEqualTo(1) // 감사 실패가 fixed 카운트를 깨지 않음
        assertThat(report.failed).isEqualTo(0)
    }

    @Test
    fun `should_한_zone_예외를_격리하고_다음_zone_계속_when_보정중_예외가_나면`() {
        // given: 두 zone 모두 누수, zone10 보정에서 예외
        every { driftStockRepository.aggregateExpectedStock(any()) } returns
            listOf(ZoneExpected(10L, 100), ZoneExpected(20L, 100))
        every { stockGateway.getAll(listOf(10L, 20L)) } returns mapOf(10L to 90, 20L to 80)
        every { stockGateway.correctStock(10L, 100) } throws RuntimeException("redis error")

        val report = service.checkDrift(now)

        verify(exactly = 1) { stockGateway.correctStock(20L, 100) } // 다음 zone 은 계속 처리
        assertThat(report.fixed).isEqualTo(1) // zone20
        assertThat(report.failed).isEqualTo(1) // zone10 격리
    }

    @Test
    fun `should_청크전체_failed_집계_when_MGET이_실패하면`() {
        every { driftStockRepository.aggregateExpectedStock(any()) } returns
            listOf(ZoneExpected(10L, 100), ZoneExpected(20L, 100))
        every { stockGateway.getAll(any()) } throws RuntimeException("redis circuit open")

        val report = service.checkDrift(now)

        verify(exactly = 0) { stockGateway.correctStock(any(), any()) }
        assertThat(report.failed).isEqualTo(2) // 청크 내 zone 수만큼 failed
    }
}
