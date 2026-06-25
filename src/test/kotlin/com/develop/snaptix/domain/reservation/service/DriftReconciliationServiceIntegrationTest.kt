package com.develop.snaptix.domain.reservation.service

import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.domain.reservation.reconcile.ReconcileIntegrationSupport
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import com.develop.snaptix.global.alert.service.AlertService
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import io.mockk.clearMocks
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID

/**
 * DriftReconciliationService 통합 테스트 (Testcontainers).
 *
 * **단위 테스트와 역할 분리**: 분기 매트릭스·DriftReport 카운트·예외 격리·holdCutoff 산술은 단위(MockK)가 덮는다.
 * 여기서는 **목으로 증명 못 하는 실제 경계만** 검증한다:
 *  - 실제 Redis 키 효과(stock SET / 불변)와 **claimed 미접촉**,
 *  - 실제 MySQL audit row 영속화(`STOCK_DRIFT_FIX`),
 *  - 키 부재 → skip(키 미생성),
 *  - 혼합 배치 1패스(누수·오버셀·정상 독립 동작),
 *  - **재실행 멱등/수렴**(중복 보정·중복 감사 없음).
 *
 * AlertService 는 스펙대로 `@Primary` mockk(Slack 발송 차단·호출만 검증). arrange/assert 는 raw 키.
 */

@SpringBootTest
@Import(DriftReconciliationServiceIntegrationTest.MockAlertConfig::class)
class DriftReconciliationServiceIntegrationTest(
    @Autowired private val driftReconciliationService: DriftReconciliationService,
    @Autowired private val stockRedisGateway: StockRedisGateway,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val keys: RedisKeyFactory,
    @Autowired private val alertService: AlertService, // @Primary mockk
) : ReconcileIntegrationSupport() {
    @TestConfiguration
    class MockAlertConfig {
        @Bean
        @Primary
        fun mockAlertService(): AlertService = mockk(relaxed = true)
    }

    @BeforeEach
    fun setUp() {
        ReconcileFixtures.cleanAll()
        clearMocks(alertService)
        justRun { alertService.notify(any()) }
    }

    @Test
    fun `should_혼합_청크에서_각_분기_정확히_동작_when_누수_오버셀_정상_존이_섞이면`() {
        // given: 3 개 zone 을 한 청크에 묶음 (CHUNK_SIZE=500 이므로 한 패스)
        val leakZone = seedZoneWithExpected(100).also { redisTemplate.opsForValue().set(keys.stock(it), "90") }
        val overZone = seedZoneWithExpected(100).also { redisTemplate.opsForValue().set(keys.stock(it), "110") }
        val sameZone = seedZoneWithExpected(100).also { redisTemplate.opsForValue().set(keys.stock(it), "100") }

        // when
        val report = driftReconciliationService.checkDrift(Instant.now())

        // then: 한 청크 안에서 각 분기가 독립적으로 동작 (실제 Redis 값)
        assertThat(stockRedisGateway.get(leakZone)).isEqualTo(100) // 누수 보정
        assertThat(stockRedisGateway.get(overZone)).isEqualTo(110) // 오버셀 불변
        assertThat(stockRedisGateway.get(sameZone)).isEqualTo(100) // 동일 무동작
        assertThat(report.fixed).isEqualTo(1)
        assertThat(report.oversell).isEqualTo(1)
        assertThat(report.unchanged).isEqualTo(1)

        // 오버셀 1건만 알림 · 감사는 누수 1건만(오버셀은 감사 없음)
        verify(exactly = 1) {
            alertService.notify(match<AlertContext> { it.trigger == AlertTrigger.STOCK_DRIFT_OVERSELL })
        }
        assertThat(ReconcileFixtures.countAudit(RedisAction.STOCK_DRIFT_FIX.name)).isEqualTo(1)
    }

    @Test
    fun `should_claimed_미접촉_when_누수_보정시`() {
        // given: 누수 + claimed 에 멤버 존재
        val zoneId = seedZoneWithExpected(100)
        redisTemplate.opsForValue().set(keys.stock(zoneId), "90")
        redisTemplate.opsForSet().add(keys.claimed(zoneId), UUID.randomUUID().toString())

        // when
        driftReconciliationService.checkDrift(Instant.now())

        // then: stock 은 보정되되 claimed 는 그대로(정합성 계약 #0: claimed 는 Rebuild 책임)
        assertThat(stockRedisGateway.get(zoneId)).isEqualTo(100)
        assertThat(redisTemplate.opsForSet().size(keys.claimed(zoneId))).isEqualTo(1)
    }

    @Test
    fun `should_skip_및_키미생성_when_stock_키가_없으면`() {
        // given: stock 키 없음(rebuild 생성 책임)
        val zoneId = seedZoneWithExpected(100)
        redisTemplate.delete(keys.stock(zoneId))

        // when
        val report = driftReconciliationService.checkDrift(Instant.now())

        // then: 키 미생성 · 보정/알림/감사 없음
        assertThat(stockRedisGateway.get(zoneId)).isNull()
        assertThat(report.skipped).isEqualTo(1)
        verify(exactly = 0) { alertService.notify(any()) }
        assertThat(ReconcileFixtures.countAudit(RedisAction.STOCK_DRIFT_FIX.name)).isEqualTo(0)
    }

    @Test
    fun `should_재실행시_수렴하고_중복보정_없음_when_두번_실행하면`() {
        // given: 누수 zone
        val zoneId = seedZoneWithExpected(100)
        redisTemplate.opsForValue().set(keys.stock(zoneId), "90")

        // when: 1차 실행 → 보정
        val first = driftReconciliationService.checkDrift(Instant.now())
        // then(1차)
        assertThat(first.fixed).isEqualTo(1)
        assertThat(stockRedisGateway.get(zoneId)).isEqualTo(100)
        assertThat(ReconcileFixtures.countAudit(RedisAction.STOCK_DRIFT_FIX.name)).isEqualTo(1)

        // when: 2차 실행 → 이미 수렴(actual == expected)
        val second = driftReconciliationService.checkDrift(Instant.now())
        // then(2차): 무동작 · 중복 보정/감사 없음(멱등)
        assertThat(second.fixed).isEqualTo(0)
        assertThat(second.unchanged).isEqualTo(1)
        assertThat(stockRedisGateway.get(zoneId)).isEqualTo(100)
        assertThat(ReconcileFixtures.countAudit(RedisAction.STOCK_DRIFT_FIX.name)).isEqualTo(1) // 그대로 1
    }

    /** 활성 이벤트 + 단일 zone(totalCapacity = expected) 시드. 예약 0건이므로 expected == capacity. */
    private fun seedZoneWithExpected(expected: Int): Long {
        val event = ReconcileFixtures.insertEvent() // 기본 ON_SALE → active
        return ReconcileFixtures.insertZone(eventId = event.eventId, capacity = expected).zoneId
    }
}
