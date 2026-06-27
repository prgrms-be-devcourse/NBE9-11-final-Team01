package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * EventKeyCleanupService 통합 테스트 (Testcontainers).
 *
 * CLOSED 이벤트의 잔존 키(event:info/stock/claimed)를 스윕이 회수하고,
 * active 이벤트 키는 보존하며, 재실행이 멱등(skipped)인지 검증.
 * 컨테이너·정리(DB·FLUSHDB)는 [IntegrationTestSupport] 담당.
 */
class EventKeyCleanupServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var eventKeyCleanupService: EventKeyCleanupService

    private fun eventInfoKey(publicId: String) = "event:info:$publicId"

    private fun stockKey(zoneId: Long) = "ZONE:$zoneId:stock"

    private fun claimedKey(zoneId: Long) = "ZONE:$zoneId:claimed"

    @Test
    fun `CLOSED 이벤트의 고아 키를 정리하고 active 이벤트 키는 보존한다`() {
        // given: CLOSED 이벤트 + 잔존 Redis 키
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED)
        val closedZone = ReconcileFixtures.insertZone(closed.eventId, capacity = 100)
        redisTemplate.opsForValue().set(eventInfoKey(closed.publicId), "{}")
        redisTemplate.opsForValue().set(stockKey(closedZone.zoneId), "0")
        redisTemplate.opsForSet().add(claimedKey(closedZone.zoneId), "o1", "o2")

        // and: active 이벤트 키(정리 대상 아님)
        val active = ReconcileFixtures.insertEvent(EventStatus.ON_SALE)
        val activeZone = ReconcileFixtures.insertZone(active.eventId, capacity = 100)
        redisTemplate.opsForValue().set(stockKey(activeZone.zoneId), "100")

        // when
        val report = eventKeyCleanupService.sweep(Instant.now())

        // then: CLOSED 키 0건 수렴
        assertThat(redisTemplate.hasKey(eventInfoKey(closed.publicId))).isFalse()
        assertThat(redisTemplate.hasKey(stockKey(closedZone.zoneId))).isFalse()
        assertThat(redisTemplate.hasKey(claimedKey(closedZone.zoneId))).isFalse()
        assertThat(report.cleaned).isEqualTo(1)

        // active 키는 보존
        assertThat(redisTemplate.hasKey(stockKey(activeZone.zoneId))).isTrue()
    }

    @Test
    fun `이미 정리된 CLOSED 이벤트를 재스윕하면 멱등하게 skipped 처리한다`() {
        val closed = ReconcileFixtures.insertEvent(EventStatus.CLOSED)
        val zone = ReconcileFixtures.insertZone(closed.eventId, capacity = 100)
        redisTemplate.opsForValue().set(stockKey(zone.zoneId), "0")

        eventKeyCleanupService.sweep(Instant.now()) // 1차: 정리

        val second = eventKeyCleanupService.sweep(Instant.now()) // 2차: 지울 게 없음

        assertThat(second.cleaned).isEqualTo(0)
        assertThat(second.skipped).isGreaterThanOrEqualTo(1)
    }
}
