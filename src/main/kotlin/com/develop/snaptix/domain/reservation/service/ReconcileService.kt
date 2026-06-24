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

    @Suppress("TooGenericExceptionCaught") // 행 단위 격리: 한 건 실패가 배치를 막지 않도록
    fun reconcileExpired(now: Instant): ReconcileReport {
        val cutoff = now.minus(reconcileProperties.holdWindow)
        val expired = reservationRepository.findExpiredPending(cutoff) // 조회된 결고값(ExpiredReservation)

        var released = 0
        var compensated = 0
        var failed = 0
        for (reservation in expired) {
            try {
                if (reservationRepository.releaseIfPending(reservation.id) != 1) {
                    continue // 이미 CONFIRMED/타 처리
                }
                released++
                if (compensate(reservation)) {
                    compensated++
                }
            } catch (e: Exception) {
                failed++
                // DB는 RELEASED지만 Redis 미보상 = 일시적 누수(drift). Drift(PR-06)/Rebuild(PR-07/08)가 교정.
                // 즉 실패기록을 남기는 것
                logger.atError {
                    message = "Reconcile failed for one reservation (left as drift)"
                    cause = e
                    payload = mapOf("reservationId" to reservation.id, "orderId" to reservation.orderId)
                }
            }
        }

        logger.atInfo {
            message = "Reconcile expired pending done"
            payload =
                mapOf(
                    "action" to RedisAction.RECONCILE_RUN.name,
                    "released" to released,
                    "compensated" to compensated,
                    "failed" to failed,
                )
        }
        return ReconcileReport(released = released, compensated = compensated, failed = failed)
    }

    /**
     * true = orderId ∈ claimed 라서 실제로 +1 & claimed 제거(보상 수행) → compensated++.
     * false = claimed에 없음(이미 보상됐거나 워커가 차감한 적 없음) → no-op.
     * @Async + @TransactionalEventListener로는 나중에 현재는 runCatching으로
     */
    private fun compensate(reservation: ExpiredReservation): Boolean {
        val done = stockRedisGateway.compensate(reservation.zoneId, UUID.fromString(reservation.orderId))
        writeAudit(reservation, done) // best-effort
        return done
    }

    // runCatching은 error도 잡기에 사용X -> try catch로
    @Suppress("TooGenericExceptionCaught")
    private fun writeAudit(
        reservation: ExpiredReservation,
        compensated: Boolean,
    ) {
        try {
            auditLogRepository.insert(
                actorId = null,
                actionType = RedisAction.RECONCILE_RUN.name,
                targetType = "RESERVATION",
                targetId = reservation.id,
                details =
                    buildJsonObject {
                        put("orderId", reservation.orderId)
                        put("zoneId", reservation.zoneId)
                        put("compensated", compensated)
                    },
            )
        } catch (e: Exception) {
            // Throwable 아님 → Error는 전파
            logger.atWarn {
                message = "Audit log insert failed (compensation already applied)"
                cause = e
                payload = mapOf("reservationId" to reservation.id)
            }
        }
    }

    // controller 관리자가 정산했다는 기록을 남길려는 것 target은 X(report에 포함)
    @Suppress("TooGenericExceptionCaught")
    internal fun writeAdminAudit(
        actorId: Long,
        report: ReconcileReport,
    ) {
        try {
            auditLogRepository.insert(
                actorId = actorId,
                actionType = "ADMIN_RECONCILE",
                targetType = null,
                targetId = null,
                details =
                    buildJsonObject {
                        put("released", report.released)
                        put("compensated", report.compensated)
                        put("failed", report.failed)
                    },
            )
        } catch (e: Exception) {
            logger.atWarn {
                message = "ADMIN_RECONCILE audit insert failed"
                cause = e
            }
        }
    }
}
