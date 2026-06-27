package com.develop.snaptix.domain.event.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * EventKeyCleanupRepository 통합 테스트 (Testcontainers).
 *
 * 스윕 후보 선정의 핵심 필터를 직접 검증:
 *  - status == CLOSED 만 (active 제외)
 *  - updated_at >= cutoff (window 밖은 제외)
 *  - 이벤트별 zoneIds 그룹핑
 *
 * NOTE: ReconcileFixtures.insertEvent 는 updated_at 을 now(기본 CurrentTimestamp)로 넣으므로,
 *       window 필터는 cutoff 인자를 과거/미래로 바꿔 검증한다(custom updated_at 불필요).
 */
class EventKeyCleanupRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var repository: EventKeyCleanupRepository

    @Test
    fun `CLOSED 이고 updated_at이 cutoff 이상인 이벤트만 후보로 반환한다`() {
        val now = Instant.now()
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED) // updated_at ≈ now
        ReconcileFixtures.insertZone(closed.eventId, capacity = 100)

        // cutoff 가 과거(now-1d) → 포함
        val included = repository.findClosedCleanupTargets(now.minus(1, ChronoUnit.DAYS))
        assertThat(included.map { it.eventPublicId }).contains(closed.publicId)

        // cutoff 가 미래(now+1h) → updated_at(now) < cutoff → window 밖 → 제외
        val excluded = repository.findClosedCleanupTargets(now.plus(1, ChronoUnit.HOURS))
        assertThat(excluded.map { it.eventPublicId }).doesNotContain(closed.publicId)
    }

    @Test
    fun `active 이벤트는 후보에서 제외한다`() {
        val onSale = ReconcileFixtures.insertEvent(EventStatus.ON_SALE)
        ReconcileFixtures.insertZone(onSale.eventId, capacity = 100)
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED)
        ReconcileFixtures.insertZone(closed.eventId, capacity = 100)

        val targets = repository.findClosedCleanupTargets(Instant.now().minus(1, ChronoUnit.DAYS))

        val ids = targets.map { it.eventPublicId }
        assertThat(ids).contains(closed.publicId)
        assertThat(ids).doesNotContain(onSale.publicId)
    }

    @Test
    fun `이벤트의 모든 zoneId를 그룹핑해 반환한다`() {
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED)
        val zoneA = ReconcileFixtures.insertZone(closed.eventId, capacity = 100)
        val zoneB = ReconcileFixtures.insertZone(closed.eventId, capacity = 50)

        val target =
            repository
                .findClosedCleanupTargets(Instant.now().minus(1, ChronoUnit.DAYS))
                .single { it.eventPublicId == closed.publicId }

        assertThat(target.zoneIds).containsExactlyInAnyOrder(zoneA.zoneId, zoneB.zoneId)
    }
}
