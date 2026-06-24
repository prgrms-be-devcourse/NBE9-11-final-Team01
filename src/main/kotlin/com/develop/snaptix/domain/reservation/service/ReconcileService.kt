package com.develop.snaptix.domain.reservation.service

import com.develop.snaptix.domain.auditlog.repository.AuditLogRepository
import com.develop.snaptix.domain.reservation.repository.ExpiredReservation
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 만료 PENDING 정산 단위 로직(공유). (작업 명세서 §6.1 · Story 13.3)
 *
 * 행 단위로 조건부 UPDATE(affected=1) → claimed 가드 보상(+1)을 수행한다.
 * 스케줄러·Admin·Rebuild(a)가 모두 본 서비스를 재사용하여 로직 중복을 만들지 않는다.
 *
 * 멱등성: affected=1 게이트 + claimed 가드 Lua의 이중 방어로 `+1`은 한 번만 적용된다.
 */
@Service
class ReconcileService(
    private val reservationRepository: ReservationRepository,
    private val stockRedisGateway: StockRedisGateway,
    private val auditLogRepository: AuditLogRepository,
    private val reconcileProperties: ReconcileProperties,
) {
    private val logger = KotlinLogging.logger {}

    fun reconcileExpired(now: Instant): ReconcileReport {
        val cutoff = now.minus(reconcileProperties.holdWindow)
        val expired = reservationRepository.findExpiredPending(cutoff) // 조회된 결고값(ExpiredReservation)

        var released = 0
        var compensated = 0
        for (reservation in expired) {
            if (reservationRepository.releaseIfPending(reservation.id) != 1) {
                continue // 0이면 이미 주문 성공되거나 다른 정산 처리
            }
            released++
            if (compensate(reservation)) {
                compensated++
            }
        }

        logger.atInfo {
            message = "Reconcile expired pending done"
            payload =
                mapOf(
                    "action" to RedisAction.RECONCILE_RUN.name,
                    "released" to released,
                    "compensated" to compensated,
                )
        }
        return ReconcileReport(released = released, compensated = compensated)
    }

    /**
     * true = orderId ∈ claimed 라서 실제로 +1 & claimed 제거(보상 수행) → compensated++.
     * false = claimed에 없음(이미 보상됐거나 워커가 차감한 적 없음) → no-op.
     */
    private fun compensate(reservation: ExpiredReservation): Boolean {
        val compensated =
            stockRedisGateway.compensate(
                reservation.zoneId,
                UUID.fromString(reservation.orderId), // ← String → UUID
            )
        auditLogRepository.insert(
            actorId = null,
            actionType = RedisAction.RECONCILE_RUN.name,
            targetType = "RESERVATION",
            targetId = reservation.id,
            details =
                buildJsonObject {
                    put("orderId", reservation.orderId)
                    put("zoneId", reservation.zoneId)
                    put("compensateResult", compensated)
                },
        )
        return compensated
    }
}
