package com.develop.snaptix.global.resilience

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import com.develop.snaptix.global.alert.service.AlertService
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import io.mockk.clearMocks
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * RebuildService 통합 테스트. (작업 명세서 v2.1 §8 AC)
 *
 * 실제 #7 부품·Redis·DB로 **수렴값/TTL/event:info 값/reconcile 연동/알림**을 검증.
 * 컨테이너·정리(DB FLUSHDB·서킷 CLOSED)는 [IntegrationTestSupport] 가 담당.
 * AlertService 만 `@Primary` mockk(Slack 차단·호출 검증).
 */
@Import(RebuildServiceIntegrationTest.MockAlertConfig::class)
class RebuildServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var rebuildService: RebuildService

    @Autowired
    private lateinit var stockRedisGateway: StockRedisGateway

    @Autowired
    private lateinit var eventCacheRedisGateway: EventCacheRedisGateway

    @Autowired
    private lateinit var keys: RedisKeyFactory

    @Autowired
    private lateinit var alertService: AlertService // @Primary mockk

    @TestConfiguration
    class MockAlertConfig {
        @Bean
        @Primary
        fun mockAlertService(): AlertService = mockk(relaxed = true)
    }

    @BeforeEach
    fun setUpMocks() {
        // 컨테이너/DB/Redis 정리는 부모 @BeforeEach(cleanAll)가 선행. 여기선 목만 초기화.
        clearMocks(alertService)
        justRun { alertService.notify(any()) }
    }

    @Test
    fun `재구축 후 stock60·claimed10·event_info(TTL,totalCapacity) 수렴하고 만료는 RELEASED, 시작·완료 알림`() {
        // given: cap 100 · CONFIRMED 30 · 유효 PENDING 10 · 만료 PENDING 5
        val now = Instant.now()
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent() // ON_SALE
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        val eventPublicId = UUID.fromString(event.publicId)

        repeat(30) {
            ReconcileFixtures.insertReservation(userId, event.eventId, zone.zoneId, ReservationStatus.CONFIRMED, now)
        }
        repeat(10) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now,
            )
        }
        // 만료 PENDING 5건(now-30분) — (a)Reconcile 에서 RELEASED 되어 점유 제외. 그중 1건 추적.
        val expiredOrderId =
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(30, ChronoUnit.MINUTES),
            )
        repeat(4) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now.minus(30, ChronoUnit.MINUTES),
            )
        }

        // when
        rebuildService.rebuild()

        // then: 수렴값
        assertThat(stockRedisGateway.get(zone.zoneId)).isEqualTo(60) // 100 − (30 + 10)
        assertThat(redisTemplate.opsForSet().size(keys.claimed(zone.zoneId))).isEqualTo(10L)

        // ② event:info — TTL(≈1h) + 실제 totalCapacity 값(누락 시 OrderIngest RECONCILE_FAILED)
        val ttl = redisTemplate.getExpire(keys.eventInfo(eventPublicId), TimeUnit.SECONDS)
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(3600L)
        val info = eventCacheRedisGateway.get(eventPublicId)
        assertThat(info).isNotNull
        assertThat(info!!.totalCapacity).isEqualTo(100)

        // ③ reconcile→snapshot 연동: 만료 PENDING 이 실제 RELEASED 됐는지(재구축이 (a)를 거쳤다는 직접 증거)
        assertThat(ReconcileFixtures.findStatus(expiredOrderId)).isEqualTo(ReservationStatus.RELEASED.name)

        // 알림
        verify { alertService.notify(match<AlertContext> { it.trigger == AlertTrigger.REBUILD_STARTED }) }
        verify { alertService.notify(match<AlertContext> { it.trigger == AlertTrigger.REBUILD_COMPLETED }) }
    }

    @Test
    fun `재실행해도 같은 값으로 수렴한다(멱등)`() {
        val now = Instant.now()
        val userId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent()
        val zone = ReconcileFixtures.insertZone(event.eventId, capacity = 100)
        repeat(30) {
            ReconcileFixtures.insertReservation(userId, event.eventId, zone.zoneId, ReservationStatus.CONFIRMED, now)
        }
        repeat(10) {
            ReconcileFixtures.insertReservation(
                userId,
                event.eventId,
                zone.zoneId,
                ReservationStatus.PENDING_PAYMENT,
                now,
            )
        }

        rebuildService.rebuild()
        rebuildService.rebuild() // 첫 실행이 락 해제 → 2회차도 정상, 동일 수렴

        assertThat(stockRedisGateway.get(zone.zoneId)).isEqualTo(60)
        assertThat(redisTemplate.opsForSet().size(keys.claimed(zone.zoneId))).isEqualTo(10L)
    }
}
