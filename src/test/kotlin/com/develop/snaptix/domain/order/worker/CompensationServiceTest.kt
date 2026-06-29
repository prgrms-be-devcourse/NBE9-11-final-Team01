package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@SpringBootTest
@DisplayName("CompensationService (통일 보상 불변식 서비스) 통합 테스트")
class CompensationServiceTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var sut: CompensationService

    @Autowired
    private lateinit var keys: RedisKeyFactory

    // Spring 공유 컨텍스트의 MeterRegistry — 카운터 누적을 방지하기 위해 @BeforeEach에서 기준값을 기록한다
    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @MockitoBean
    private lateinit var reservationRepository: ReservationRepository

    private val zoneId = 777L
    private lateinit var stockKey: String
    private lateinit var claimedKey: String

    // 공유 컨텍스트에서의 카운터 누적 방지 — 테스트마다 시작값을 스냅샷
    private var initialCompensateCount = 0.0

    @BeforeEach
    fun setUp() {
        stockKey = keys.stock(zoneId)
        claimedKey = keys.claimed(zoneId)

        // 각 테스트 격리를 위해 Redis 키 초기화
        redisTemplate.delete(listOf(stockKey, claimedKey))

        // 공유 MeterRegistry 누적 방어: 시작 시점의 카운터 값을 기록
        initialCompensateCount =
            meterRegistry
                .find("ticketing.stock.compensate.count")
                .counter()
                ?.count() ?: 0.0
    }

    @Nested
    @DisplayName("compensateIfLeaked() 3단계 가드 검증")
    inner class CompensateIfLeakedTest {
        @Test
        @DisplayName("Step 1: Redis Claimed에 존재하지 않으면 DB 조회 없이 즉시 종료된다 (No-op)")
        fun `skips and does no db lookup if not in claimed set`() {
            // given
            val orderId = UUID.randomUUID()
            val initialStock = 20
            redisTemplate.opsForValue().set(stockKey, initialStock.toString())
            // claimed 집합에는 넣지 않음

            // when
            sut.compensateIfLeaked(orderId, zoneId)

            // then
            val actualStock = redisTemplate.opsForValue().get(stockKey)?.toInt()
            assertThat(actualStock).isEqualTo(initialStock)

            // [Java Mockito] DB 조회가 단 한 번도 발생하지 않았음을 검증
            verify(reservationRepository, never()).findByOrderId(anyString())

            // [메트릭] 보상 없으므로 compensate_count 미증가
            assertThat(compensateDelta()).isZero()
        }

        @Test
        @DisplayName("Step 2: Claimed에 존재하지만 DB에 커밋된 예약이 있으면 보상을 스킵한다 (이중 보상 방어)")
        fun `skips compensation if db row already exists`() {
            // given
            val orderId = UUID.randomUUID()
            val initialStock = 20
            redisTemplate.opsForValue().set(stockKey, initialStock.toString())
            redisTemplate.opsForSet().add(claimedKey, orderId.toString())

            // [Java Mockito] DB 조회 시 임의의 Mock 객체(행 존재)를 반환하도록 설정
            // 코틀린 예약어 충돌을 피하기 위해 BDDMockito.given 사용
            given(reservationRepository.findByOrderId(orderId.toString()))
                .willReturn(mock())

            // when
            sut.compensateIfLeaked(orderId, zoneId)

            // then
            val actualStock = redisTemplate.opsForValue().get(stockKey)?.toInt()
            val isMember = redisTemplate.opsForSet().isMember(claimedKey, orderId.toString())

            assertThat(actualStock).isEqualTo(initialStock) // 재고 변화 없음
            assertThat(isMember).isTrue() // Claimed에서도 제거되지 않음

            // [Java Mockito] DB 조회가 정확히 1회 일어났는지 검증
            verify(reservationRepository, times(1)).findByOrderId(orderId.toString())

            // [메트릭] 이중 보상 방어로 스킵됐으므로 compensate_count 미증가
            assertThat(compensateDelta()).isZero()
        }

        @Test
        @DisplayName("Step 3: Claimed에 존재하고 DB 행이 없을 때만 정상적으로 원자적 보상(+1, SREM)이 수행된다")
        fun `compensates stock atomically if in claimed and no db row exists`() {
            // given
            val orderId = UUID.randomUUID()
            val initialStock = 20
            redisTemplate.opsForValue().set(stockKey, initialStock.toString())
            redisTemplate.opsForSet().add(claimedKey, orderId.toString())

            // [Java Mockito] DB 행이 없음을 설정
            given(reservationRepository.findByOrderId(orderId.toString()))
                .willReturn(null)

            // when
            sut.compensateIfLeaked(orderId, zoneId)

            // then
            val actualStock = redisTemplate.opsForValue().get(stockKey)?.toInt()
            val isMember = redisTemplate.opsForSet().isMember(claimedKey, orderId.toString())

            assertThat(actualStock).isEqualTo(initialStock + 1) // 재고 복구 완료
            assertThat(isMember).isFalse() // Claimed 정리 완료

            // [Java Mockito] DB 조회가 1회 일어났는지 검증
            verify(reservationRepository, times(1)).findByOrderId(orderId.toString())

            // [메트릭] 보상 완료 시 compensate_count +1
            assertThat(compensateDelta()).isEqualTo(1.0)
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * 공유 MeterRegistry의 compensate_count 카운터를 테스트 시작 시점 기준으로 델타 계산.
     * 스프링 컨텍스트가 공유되므로 절댓값 대신 증분만 검증한다.
     */
    private fun compensateDelta(): Double = (
        meterRegistry
            .find("ticketing.stock.compensate.count")
            .counter()
            ?.count() ?: 0.0
    ) - initialCompensateCount
}
