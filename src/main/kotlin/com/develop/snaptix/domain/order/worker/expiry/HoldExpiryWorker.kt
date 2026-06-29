package com.develop.snaptix.domain.order.worker.expiry

import com.develop.snaptix.domain.order.worker.release.ReleaseReason
import com.develop.snaptix.domain.order.worker.release.StockReleaseService
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * ORDER_HOLD 5분 만료 주문을 RELEASED로 전이하고 재고를 복구하는 배치 워커. (이슈 #10, Story 8-1)
 *
 * ## 설계 원칙
 * - **정합성 정답 소스**: DB 폴링 배치(`@Scheduled`).
 *   [HoldExpiryKeyspaceListener](보조)와 달리 Redis 재시작·장애 시에도 누락 없이 처리한다.
 * - **호출자 계약 준수**: `releaseIfPending()` 의 조건부 UPDATE `affected = 1` 확인 후
 *   [StockReleaseService.release] 호출. `affected = 0`(이미 CONFIRMED·타 워커 처리) → no-op.
 * - **재고 이중 복구 방지**: 다중 인스턴스 환경에서도 조건부 UPDATE 가 단일 처리를 보장한다.
 * - **compensate() 실패 시**: DB 는 이미 RELEASED 확정, Redis 재고 누수 발생.
 *   ERROR 로그 + 메트릭으로 가시화하고, S-13 드리프트 정산(30분 주기)이 재고를 보정한다.
 *
 * ## 배치 크기 제한
 * 대량 만료 방어를 위해 한 번의 배치에서 조회·처리할 최대 건수를 `order.hold.batch-size`로 제한한다.
 * 한 주기에 처리 못 한 건은 다음 주기에 자동으로 처리된다.
 */
@Component
class HoldExpiryWorker(
    private val reservationRepository: ReservationRepository,
    private val stockReleaseService: StockReleaseService,
    private val meterRegistry: MeterRegistry,
    @Value("\${order.hold.timeout-minutes:5}") timeoutMinutes: Long,
    @Value("\${order.hold.batch-size:100}") private val batchSize: Int,
) {
    private val logger = KotlinLogging.logger {}
    private val holdTimeout: Duration = Duration.ofMinutes(timeoutMinutes)

    /**
     * 만료 PENDING 주문 스캔 및 릴리즈.
     *
     * `fixedDelay` 사용 — 이전 실행이 완료된 후 delay 만큼 대기한 뒤 다음 실행 시작.
     * 동일 인스턴스 내 중첩 실행 없음.
     */
    @Suppress("TooGenericExceptionCaught") // 예약 단위 오류 격리 — 나머지 예약 처리 보장
    @Scheduled(fixedDelayString = "\${order.hold.scheduler-fixed-delay-ms:60000}")
    fun expireHeldOrders() {
        val cutoff = Instant.now().minus(holdTimeout)
        val expired = reservationRepository.findExpiredPendingPaged(cutoff, batchSize)

        if (expired.isEmpty()) return

        logger.info { "[HOLD_EXPIRY] 만료 대상 조회 — count=${expired.size}, cutoff=$cutoff" }

        var releasedCount = 0
        var skippedCount = 0

        for (reservation in expired) {
            try {
                val affected = reservationRepository.releaseIfPending(reservation.id)

                if (affected == 1) {
                    // DB RELEASED 확정 후 Redis 후처리 위임 (호출자 계약 준수)
                    stockReleaseService.release(
                        orderId = reservation.orderId,
                        zoneId = reservation.zoneId,
                        reason = ReleaseReason.PAYMENT_TIMEOUT,
                    )
                    releasedCount++
                    meterRegistry.counter(METRIC_RELEASED_COUNT).increment()
                    logger.debug {
                        "[HOLD_EXPIRY] 릴리즈 완료 — orderId=${reservation.orderId}, zoneId=${reservation.zoneId}"
                    }
                } else {
                    // affected == 0: 이미 CONFIRMED·RELEASED·CANCELLED → no-op (이중 복구 방지)
                    skippedCount++
                    logger.debug {
                        "[HOLD_EXPIRY] 이미 처리된 주문 스킵 — id=${reservation.id}, orderId=${reservation.orderId}"
                    }
                }
            } catch (e: Exception) {
                // compensate() 실패: DB 는 RELEASED 확정이나 Redis 재고 누수 발생
                // → S-13 드리프트 정산이 30분 주기로 보정
                logger.error(e) {
                    "[HOLD_EXPIRY][STOCK_RELEASE_FAILED] 재고 복구 실패 — " +
                        "orderId=${reservation.orderId}, zoneId=${reservation.zoneId}"
                }
                meterRegistry.counter(METRIC_RELEASE_ERROR_COUNT).increment()
            }
        }

        logger.info {
            "[HOLD_EXPIRY] 배치 완료 — releasedCount=$releasedCount, skippedCount=$skippedCount"
        }
    }

    private companion object {
        const val METRIC_RELEASED_COUNT = "ticketing.order.hold.released.count"
        const val METRIC_RELEASE_ERROR_COUNT = "ticketing.order.hold.release.error.count"
    }
}
