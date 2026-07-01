package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.observability.LogAction
import com.develop.snaptix.domain.order.observability.OrderMetrics
import com.develop.snaptix.domain.order.worker.port.CompensationPort
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 재고 보상 불변식 포트 구현체.
 *
 * [이슈 #7] 커밋된 DB 행 없음 재조회 가드로 재고 오버카운트 엣지 케이스를 방지한다.
 * [이슈 #14] @LogAction(COMPENSATE_STOCK) 구조화 로그 자동 주입.
 *            보상 성공 시 [OrderMetrics.COMPENSATE_COUNT] +1.
 */
@Service
class CompensationService(
    private val stockRedisGateway: StockRedisGateway,
    private val reservationRepository: ReservationRepository,
    private val meterRegistry: MeterRegistry,
) : CompensationPort {
    private val log = KotlinLogging.logger {}

    @LogAction("COMPENSATE_STOCK")
    @Suppress("TooGenericExceptionCaught")
    override fun compensateIfLeaked(
        orderId: UUID,
        zoneId: Long,
    ) {
        try {
            // Step 1: Claimed 멤버십 선검사 — Redis 에 차감 이력 없으면 즉시 no-op
            if (!stockRedisGateway.isClaimed(zoneId, orderId)) {
                logSkip("SKIP_NOT_CLAIMED", orderId, zoneId)
                return
            }
            // Step 2: DB 재조회 가드 — 커밋된 행이 있으면 이중 보상 방지
            if (hasCommittedReservation(orderId)) return
            // Step 3: Lua 원자 보상 (+1 stock, SREM claimed)
            executeCompensation(orderId, zoneId)
        } catch (e: Exception) {
            log.atError {
                message = "Stock compensation failed — possible state inconsistency"
                cause = e
                payload =
                    mapOf(
                        "action" to "COMPENSATE_STOCK",
                        "result" to "ERROR",
                        "orderId" to orderId,
                        "zoneId" to zoneId,
                    )
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * DB 에 커밋된 예약 행이 존재하면 true 를 반환하고 WARN 로그를 남긴다.
     * 행이 있는 경우 보상을 생략해야 하므로 호출부에서 즉시 return 해야 한다.
     */
    private fun hasCommittedReservation(orderId: UUID): Boolean {
        val existing = reservationRepository.findByOrderId(orderId.toString()) ?: return false
        log.atWarn {
            message = "Compensation skipped — committed reservation row exists"
            payload =
                mapOf(
                    "action" to "COMPENSATE_STOCK",
                    "result" to "SKIP_ROW_EXISTS",
                    "orderId" to orderId,
                    "status" to existing.status,
                )
        }
        return true
    }

    /** Lua 원자 보상 실행 후 메트릭 증가 및 결과 로그 기록. */
    private fun executeCompensation(
        orderId: UUID,
        zoneId: Long,
    ) {
        if (stockRedisGateway.compensate(zoneId, orderId)) {
            meterRegistry.counter(OrderMetrics.COMPENSATE_COUNT).increment()
            log.atInfo {
                message = "Stock compensation succeeded"
                payload =
                    mapOf(
                        "action" to "COMPENSATE_STOCK",
                        "result" to "SUCCESS",
                        "orderId" to orderId,
                        "zoneId" to zoneId,
                    )
            }
        } else {
            log.atWarn {
                message = "Stock compensation no-op — already removed between SISMEMBER and Lua"
                payload =
                    mapOf(
                        "action" to "COMPENSATE_STOCK",
                        "result" to "NO_OP",
                        "orderId" to orderId,
                    )
            }
        }
    }

    private fun logSkip(
        result: String,
        orderId: UUID,
        zoneId: Long,
    ) {
        log.atDebug {
            message = "Compensation skipped — not in claimed set"
            payload =
                mapOf(
                    "action" to "COMPENSATE_STOCK",
                    "result" to result,
                    "orderId" to orderId,
                    "zoneId" to zoneId,
                )
        }
    }
}
