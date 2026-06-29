package com.develop.snaptix.domain.order.worker.expiry

import com.develop.snaptix.domain.order.worker.release.ReleaseReason
import com.develop.snaptix.domain.order.worker.release.StockReleaseService
import com.develop.snaptix.domain.reservation.repository.ExpiredReservation
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [HoldExpiryWorker] 단위 테스트.
 *
 * ## 전략
 * - [ReservationRepository] / [StockReleaseService] 는 mockk 으로 대체해 비즈니스 로직만 검증
 * - [SimpleMeterRegistry] 로 메트릭 카운터를 격리 검증
 * - 스케줄러 트리거 없이 [HoldExpiryWorker.expireHeldOrders] 를 직접 호출
 *
 * ## 커버하는 AC (이슈 #10)
 * 1. 만료 예약 없음 → release() 미호출
 * 2. affected=1 → PAYMENT_TIMEOUT release() 호출 + 메트릭 증가
 * 3. affected=0 (결제 성공 경합) → release() 미호출 (재고 이중 복구 방지)
 * 4. release() 예외 → 에러 메트릭 증가, 다음 건 계속 처리 (격리)
 * 5. 여러 건 중 일부만 성공 → 성공 건만 메트릭 증가
 * 6. batchSize → findExpiredPendingPaged limit 파라미터로 전달
 */
@DisplayName("HoldExpiryWorker 단위 테스트")
class HoldExpiryWorkerTest {
    private val reservationRepository: ReservationRepository = mockk()
    private val stockReleaseService: StockReleaseService = mockk()
    private lateinit var meterRegistry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
    }

    private fun createWorker(
        timeoutMinutes: Long = 5L,
        batchSize: Int = 100,
    ) = HoldExpiryWorker(
        reservationRepository = reservationRepository,
        stockReleaseService = stockReleaseService,
        meterRegistry = meterRegistry,
        timeoutMinutes = timeoutMinutes,
        batchSize = batchSize,
    )

    // ── 만료 예약 없음 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("만료 예약 없음")
    inner class NoExpiredReservations {
        @Test
        @DisplayName("만료된 예약이 없으면 StockReleaseService를 호출하지 않는다")
        fun `does not call release when no expired reservations`() {
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns emptyList()

            createWorker().expireHeldOrders()

            verify(exactly = 0) { stockReleaseService.release(any(), any(), any()) }
        }
    }

    // ── 정상 릴리즈 흐름 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 릴리즈 흐름 (affected=1)")
    inner class HappyPath {
        @Test
        @DisplayName("affected=1이면 PAYMENT_TIMEOUT으로 release()를 호출하고 released 메트릭을 증가시킨다")
        fun `calls release with PAYMENT_TIMEOUT and increments metric when affected is 1`() {
            val reservation = expiredReservation(id = 1L, orderId = "order-1", zoneId = 10L)
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns listOf(reservation)
            every { reservationRepository.releaseIfPending(1L) } returns 1
            every { stockReleaseService.release(any(), any(), any()) } just Runs

            createWorker().expireHeldOrders()

            verify(exactly = 1) {
                stockReleaseService.release("order-1", 10L, ReleaseReason.PAYMENT_TIMEOUT)
            }
            assertThat(releasedCount()).isEqualTo(1.0)
        }

        @Test
        @DisplayName("여러 만료 예약이 모두 affected=1이면 각각 release()를 호출한다")
        fun `calls release for each reservation when all affected is 1`() {
            val reservations =
                listOf(
                    expiredReservation(id = 1L, orderId = "order-1", zoneId = 10L),
                    expiredReservation(id = 2L, orderId = "order-2", zoneId = 20L),
                    expiredReservation(id = 3L, orderId = "order-3", zoneId = 30L),
                )
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns reservations
            every { reservationRepository.releaseIfPending(1L) } returns 1
            every { reservationRepository.releaseIfPending(2L) } returns 1
            every { reservationRepository.releaseIfPending(3L) } returns 1
            every { stockReleaseService.release(any(), any(), any()) } just Runs

            createWorker().expireHeldOrders()

            verify(exactly = 3) { stockReleaseService.release(any(), any(), ReleaseReason.PAYMENT_TIMEOUT) }
            assertThat(releasedCount()).isEqualTo(3.0)
        }
    }

    // ── 경합 — no-op ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("경합 처리 (affected=0)")
    inner class AlreadyProcessed {
        @Test
        @DisplayName("affected=0이면 release()를 호출하지 않는다 (결제 성공·타 워커와 경합)")
        fun `does not call release when affected is 0`() {
            val reservation = expiredReservation(id = 1L, orderId = "order-1", zoneId = 10L)
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns listOf(reservation)
            every { reservationRepository.releaseIfPending(1L) } returns 0

            createWorker().expireHeldOrders()

            verify(exactly = 0) { stockReleaseService.release(any(), any(), any()) }
            assertThat(releasedCount()).isZero()
        }

        @Test
        @DisplayName("여러 건 중 일부만 affected=1이면 해당 건만 release()를 호출한다")
        fun `calls release only for affected=1 reservations`() {
            val reservations =
                listOf(
                    expiredReservation(id = 1L, orderId = "order-confirmed", zoneId = 10L), // 이미 결제 완료
                    expiredReservation(id = 2L, orderId = "order-timeout", zoneId = 20L), // 타임아웃 처리
                )
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns reservations
            every { reservationRepository.releaseIfPending(1L) } returns 0
            every { reservationRepository.releaseIfPending(2L) } returns 1
            every { stockReleaseService.release(any(), any(), any()) } just Runs

            createWorker().expireHeldOrders()

            verify(exactly = 0) { stockReleaseService.release("order-confirmed", 10L, any()) }
            verify(exactly = 1) { stockReleaseService.release("order-timeout", 20L, ReleaseReason.PAYMENT_TIMEOUT) }
            assertThat(releasedCount()).isEqualTo(1.0)
        }
    }

    // ── release() 예외 격리 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("release() 예외 격리")
    inner class ReleaseFailure {
        @Test
        @DisplayName("release() 실패 시 에러 메트릭을 증가시키고 다음 예약 처리를 계속한다")
        fun `increments error metric and continues processing next reservation when release throws`() {
            val reservations =
                listOf(
                    expiredReservation(id = 1L, orderId = "order-fail", zoneId = 10L),
                    expiredReservation(id = 2L, orderId = "order-ok", zoneId = 20L),
                )
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns reservations
            every { reservationRepository.releaseIfPending(1L) } returns 1
            every { reservationRepository.releaseIfPending(2L) } returns 1
            every {
                stockReleaseService.release("order-fail", 10L, any())
            } throws RuntimeException("Redis compensate 실패")
            every { stockReleaseService.release("order-ok", 20L, any()) } just Runs

            createWorker().expireHeldOrders()

            // 실패 건 → 에러 메트릭
            assertThat(releaseErrorCount()).isEqualTo(1.0)
            // 성공 건 → 정상 메트릭
            assertThat(releasedCount()).isEqualTo(1.0)
            // 실패 이후에도 다음 건 처리 확인
            verify(exactly = 1) { stockReleaseService.release("order-ok", 20L, ReleaseReason.PAYMENT_TIMEOUT) }
        }

        @Test
        @DisplayName("모든 release()가 실패해도 예외가 외부로 전파되지 않는다")
        fun `does not propagate exception even when all releases fail`() {
            val reservation = expiredReservation(id = 1L, orderId = "order-1", zoneId = 10L)
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns listOf(reservation)
            every { reservationRepository.releaseIfPending(1L) } returns 1
            every { stockReleaseService.release(any(), any(), any()) } throws RuntimeException("Redis 전체 장애")

            assertDoesNotThrow { createWorker().expireHeldOrders() }
            assertThat(releaseErrorCount()).isEqualTo(1.0)
        }

        @Test
        @DisplayName("release() 예외 시 해당 건의 released 메트릭은 증가하지 않는다")
        fun `does not increment released metric when release throws`() {
            val reservation = expiredReservation(id = 1L, orderId = "order-1", zoneId = 10L)
            every { reservationRepository.findExpiredPendingPaged(any(), any()) } returns listOf(reservation)
            every { reservationRepository.releaseIfPending(1L) } returns 1
            every { stockReleaseService.release(any(), any(), any()) } throws RuntimeException("오류")

            createWorker().expireHeldOrders()

            assertThat(releasedCount()).isZero()
            assertThat(releaseErrorCount()).isEqualTo(1.0)
        }
    }

    // ── 배치 크기 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("배치 크기(batchSize)")
    inner class BatchSizeVerification {
        @Test
        @DisplayName("batchSize를 findExpiredPendingPaged의 limit 파라미터로 전달한다")
        fun `passes batchSize as limit parameter to findExpiredPendingPaged`() {
            every { reservationRepository.findExpiredPendingPaged(any(), eq(50)) } returns emptyList()

            createWorker(batchSize = 50).expireHeldOrders()

            verify { reservationRepository.findExpiredPendingPaged(any(), 50) }
        }

        @Test
        @DisplayName("기본 batchSize(100)를 limit으로 전달한다")
        fun `passes default batchSize 100 as limit`() {
            every { reservationRepository.findExpiredPendingPaged(any(), eq(100)) } returns emptyList()

            createWorker().expireHeldOrders()

            verify { reservationRepository.findExpiredPendingPaged(any(), 100) }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun expiredReservation(
        id: Long,
        orderId: String,
        zoneId: Long,
    ) = ExpiredReservation(id = id, orderId = orderId, zoneId = zoneId)

    private fun releasedCount(): Double = meterRegistry.counter("ticketing.order.hold.released.count").count()

    private fun releaseErrorCount(): Double = meterRegistry.counter("ticketing.order.hold.release.error.count").count()
}
