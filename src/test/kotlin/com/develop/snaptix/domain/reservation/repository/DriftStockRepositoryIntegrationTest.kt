package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.domain.reservation.reconcile.ReconcileIntegrationSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * DriftStockRepository.aggregateExpectedStock 통합 테스트 (Testcontainers).
 *
 * 단일 JOIN+GROUP BY 집계가 `expected = cap − (CONFIRMED + 유효 PENDING)` 를 정확히 산정하는지,
 * active 필터·LEFT JOIN(예약 0 zone 포함)·만료 PENDING 제외가 동작하는지 검증한다.
 */

@SpringBootTest
class DriftStockRepositoryIntegrationTest(
    @Autowired private val driftStockRepository: DriftStockRepository,
) : ReconcileIntegrationSupport() {
    @BeforeEach
    fun setUp() = ReconcileFixtures.cleanAll().let {}

    @Test
    fun `should_expected를_cap에서_CONFIRMED와_유효PENDING_뺀_값으로_산정_when_만료PENDING은_제외하면`() {
        // given
        val now = Instant.now()
        val cutoff = now.minus(5, ChronoUnit.MINUTES)
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent() // 기본 ON_SALE (active)
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)

        // CONFIRMED 2건 (시간 무관 점유)
        repeat(2) {
            ReconcileFixtures.insertReservation(userId, event.eventId, zone.zoneId, ReservationStatus.CONFIRMED, now)
        }
        // 유효 PENDING 3건 (now-1분 ≥ cutoff)
        repeat(3) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(1, ChronoUnit.MINUTES),
            )
        }
        // 만료 PENDING 4건 (now-10분 < cutoff) → 제외돼야 함
        repeat(4) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(10, ChronoUnit.MINUTES),
            )
        }

        // when
        val byZone = driftStockRepository.aggregateExpectedStock(cutoff).associate { it.zoneId to it.expected }

        // then: 100 − (2 CONFIRMED + 3 유효 PENDING) = 95
        assertThat(byZone[zone.zoneId]).isEqualTo(95)
    }

    @Test
    fun `should_예약0_zone도_expected_capacity로_포함_when_LEFT_JOIN이면`() {
        val now = Instant.now()
        val cutoff = now.minus(5, ChronoUnit.MINUTES)
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent()
        val zoneA = ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        val zoneB = ReconcileFixtures.insertZone(event.eventId, capacity = 50) // 예약 0
        ReconcileFixtures.insertReservation(userId, event.eventId, zoneA.zoneId, ReservationStatus.CONFIRMED, now)

        val byZone = driftStockRepository.aggregateExpectedStock(cutoff).associate { it.zoneId to it.expected }

        assertThat(byZone[zoneA.zoneId]).isEqualTo(99) // 100 - 1
        assertThat(byZone[zoneB.zoneId]).isEqualTo(50) // 예약 0 → capacity 그대로 포함
    }

    @Test
    fun `should_CLOSED_이벤트의_zone은_제외_when_active_필터면`() {
        val now = Instant.now()
        val cutoff = now.minus(5, ChronoUnit.MINUTES)
        val onSale = ReconcileFixtures.insertEvent(EventStatus.ON_SALE)
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED)
        val zoneActive = ReconcileFixtures.insertZone(onSale.eventId, capacity = 100)
        val zoneClosed = ReconcileFixtures.insertZone(closed.eventId, capacity = 100)

        val byZone = driftStockRepository.aggregateExpectedStock(cutoff).associate { it.zoneId to it.expected }

        assertThat(byZone).containsKey(zoneActive.zoneId)
        assertThat(byZone).doesNotContainKey(zoneClosed.zoneId)
    }
}
