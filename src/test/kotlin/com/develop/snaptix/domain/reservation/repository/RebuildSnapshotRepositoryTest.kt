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
 * RebuildSnapshotRepository 통합 테스트 (Testcontainers).
 *
 * 단일 트랜잭션·batched 쿼리가 zone별 `stock = cap − (CONFIRMED + 유효 PENDING)`,
 * claimed orderId 목록, event `totalCapacity`(zone 합산)를 정확히 산정하고,
 * 윈도우 밖 PENDING 제외 / active(status != CLOSED) 필터가 동작하는지 검증한다.
 *
 * NOTE: 본 repo 는 Reconcile 을 하지 않는다 — 만료 PENDING 은 `holdCutoff` 시간 조건으로 제외될 뿐이다.
 */

@SpringBootTest
class RebuildSnapshotRepositoryTest(
    @Autowired private val repository: RebuildSnapshotRepository,
) : ReconcileIntegrationSupport() {
    @BeforeEach
    fun setUp() = ReconcileFixtures.cleanAll().let {}

    @Test
    fun `should_stock과_claimed와_totalCapacity_산정_when_윈도우밖_PENDING은_제외하면`() {
        val now = Instant.now()
        val cutoff = now.minus(5, ChronoUnit.MINUTES)
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent() // ON_SALE
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)

        // CONFIRMED 2
        repeat(2) {
            ReconcileFixtures.insertReservation(userId, event.eventId, zone.zoneId, ReservationStatus.CONFIRMED, now)
        }
        // 유효 PENDING 3 (now-1분 >= cutoff) — claimed 대상
        val validOrderIds =
            (1..3).map {
                ReconcileFixtures.insertReservation(
                    userId,
                    event.eventId,
                    zone.zoneId,
                    ReservationStatus.PENDING_PAYMENT,
                    now.minus(1, ChronoUnit.MINUTES),
                )
            }
        // 윈도우 밖 PENDING 2 (now-10분 < cutoff) — 제외 대상
        repeat(2) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(10, ChronoUnit.MINUTES),
            )
        }

        val snapshot = repository.read(cutoff)

        val eventData = snapshot.events.single { it.event.id == event.eventId }
        val zoneData = eventData.zones.single { it.zoneId == zone.zoneId }
        assertThat(zoneData.stock).isEqualTo(95) // 100 − (2 + 3)
        assertThat(zoneData.claimedOrderIds).containsExactlyInAnyOrderElementsOf(validOrderIds)
        assertThat(eventData.totalCapacity).isEqualTo(100)
    }

    @Test
    fun `should_여러_zone_합계로_totalCapacity_산정_when_read하면`() {
        val event = ReconcileFixtures.insertEvent()
        ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        ReconcileFixtures.insertZone(event.eventId, capacity = 50)

        val snapshot = repository.read(Instant.now())

        val eventData = snapshot.events.single { it.event.id == event.eventId }
        assertThat(eventData.totalCapacity).isEqualTo(150)
        assertThat(eventData.zones).hasSize(2) // 예약 0 zone 도 LEFT JOIN 으로 포함
    }

    @Test
    fun `should_CLOSED_이벤트_제외_when_read하면`() {
        val onSale = ReconcileFixtures.insertEvent(EventStatus.ON_SALE)
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED)
        ReconcileFixtures.insertZone(onSale.eventId, capacity = 100)
        ReconcileFixtures.insertZone(closed.eventId, capacity = 100)

        val snapshot = repository.read(Instant.now())

        val eventIds = snapshot.events.map { it.event.id }
        assertThat(eventIds).contains(onSale.eventId)
        assertThat(eventIds).doesNotContain(closed.eventId)
    }
}
