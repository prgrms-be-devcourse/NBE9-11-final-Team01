package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test

@SpringBootTest
class ReservationRepositoryTest(
    @Autowired private val reservationRepository: ReservationRepository,
) : IntegrationTestSupport() {
    @BeforeEach
    fun setUp() = ReconcileFixtures.cleanAll().let {}

    @Test
    fun `점유 0인 zone은 결과 Map에 키가 없다(sparse) - 소비자는 0으로 처리`() {
        val now = Instant.now()
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent()
        val zoneA = ReconcileFixtures.insertZone(event.eventId, 100)
        val zoneB = ReconcileFixtures.insertZone(event.eventId, 50) // 예약 0
        ReconcileFixtures.insertReservation(
            userId,
            event.eventId,
            zoneA.zoneId,
            ReservationStatus.CONFIRMED,
            now.minus(1, ChronoUnit.MINUTES),
        )

        val occupied = reservationRepository.countOccupiedByZone(event.eventId, now.minus(5, ChronoUnit.MINUTES))

        assertThat(occupied[zoneA.zoneId]).isEqualTo(1)
        assertThat(occupied).doesNotContainKey(zoneB.zoneId) // ← 0 점유 zone 키 부재
        assertThat(occupied.getOrDefault(zoneB.zoneId, 0)).isEqualTo(0) // ← 소비자 계약(?: 0)
    }

    @Test
    fun `유효 점유 = CONFIRMED + 윈도우 내 PENDING, 만료 PENDING은 제외`() {
        val now = Instant.now()
        val holdCutoff = now.minus(5, ChronoUnit.MINUTES)
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent()
        val zone = ReconcileFixtures.insertZone(event.eventId, 100)

        // CONFIRMED 2건 (시간 무관하게 점유)
        repeat(2) {
            ReconcileFixtures.insertReservation(userId, event.eventId, zone.zoneId, ReservationStatus.CONFIRMED, now)
        }
        // 유효 PENDING 3건 (윈도우 내: now-1분 >= cutoff)
        repeat(3) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(1, ChronoUnit.MINUTES),
            )
        }
        // 만료 PENDING 4건 (윈도우 밖: now-10분 < cutoff) → 제외돼야 함
        repeat(4) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(10, ChronoUnit.MINUTES),
            )
        }

        val occupied = reservationRepository.countOccupiedByZone(event.eventId, holdCutoff)

        assertThat(occupied[zone.zoneId]).isEqualTo(5) // 2 CONFIRMED + 3 유효 PENDING (만료 4건 제외)
    }

    @Test
    fun `다른 이벤트의 예약은 카운트하지 않는다`() {
        val now = Instant.now()
        val userId = ReconcileFixtures.insertUser()
        val target = ReconcileFixtures.insertEvent()
        val other = ReconcileFixtures.insertEvent()
        val zoneTarget = ReconcileFixtures.insertZone(target.eventId, 100) // target 이벤트의 좌석 준비
        val zoneOther = ReconcileFixtures.insertZone(other.eventId, 100)
        ReconcileFixtures.insertReservation(userId, other.eventId, zoneOther.zoneId, ReservationStatus.CONFIRMED, now)

        val occupied = reservationRepository.countOccupiedByZone(target.eventId, now.minus(5, ChronoUnit.MINUTES))

        assertThat(occupied).isEmpty() // target 이벤트엔 점유 없음 → 빈 Map(증명)
    }
}
