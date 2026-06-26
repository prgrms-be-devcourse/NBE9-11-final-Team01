package com.develop.snaptix.global.resilience

import com.develop.snaptix.domain.reservation.repository.RebuildSnapshot
import com.develop.snaptix.domain.reservation.service.ReconcileService
import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import com.develop.snaptix.global.alert.service.AlertService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 서킷 복구(CLOSED) 시 1회 전체 재구축 오케스트레이션. (작업 명세서 v2.1 §8 · Story 13.2)
 *
 * 순서 고정: (a) Reconcile → (b) event:info → (c+d) stock·claimed.
 *  - 다중 인스턴스 단일 실행: 락 미획득 시 즉시 no-op.
 *  - 전 구간 Read-Only 경계 + 단일 일관 스냅샷. `now` 는 진입 시 1회 고정(계약 #0).
 *  - 시작/완료/실패 알림. 실패 시에도 finally 에서 Read-Only·락 반드시 해제.
 */
@Component
class RebuildService(
    private val reconcileService: ReconcileService,
    private val rebuildSnapshotReader: RebuildSnapshotReader,
    private val rebuildRedisWriter: RebuildRedisWriter,
    private val rebuildCoordinator: RebuildCoordinator,
    private val readOnlyModeHolder: ReadOnlyModeHolder,
    private val alertService: AlertService,
    @Qualifier("alertClock") private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught") // 광역 catch: 어느 단계 실패든 Read-Only·락 해제 보장
    fun rebuild() {
        if (!rebuildCoordinator.tryAcquire()) {
            logger.atInfo { message = "Rebuild skipped: lock not acquired (another instance running)" }
            return
        }

        readOnlyModeHolder.enable()
        val now = Instant.now(clock) // 진입 시 1회 고정 → 모든 단계 공유
        alertService.notify(AlertContext(trigger = AlertTrigger.REBUILD_STARTED))

        try {
            reconcileService.reconcileExpired(now) // (a) 만료 PENDING → RELEASED 먼저
            val snapshot = rebuildSnapshotReader.read(now) // 단일 일관 스냅샷
            applySnapshot(snapshot) // (b) event:info → (c+d) stock·claimed
            notifyCompleted(snapshot, startedAt = now)
        } catch (e: Exception) {
            alertService.notify(
                AlertContext(
                    trigger = AlertTrigger.REBUILD_FAILED,
                    fields = mapOf("error" to (e.message ?: e::class.simpleName)),
                ),
            )
            logger.atError {
                message = "Rebuild failed - manual intervention required"
                cause = e
            }
        } finally {
            readOnlyModeHolder.disable()
            rebuildCoordinator.release()
        }
    }

    private fun applySnapshot(snapshot: RebuildSnapshot) {
        snapshot.events.forEach { eventData ->
            // (b) event:info (TTL 1h, totalCapacity 포함)
            rebuildRedisWriter.writeEventInfo(eventData.event, eventData.totalCapacity)
            eventData.zones.forEach { zone ->
                // (c+d) stock SET + claimed 원자 덮어쓰기 1회 (직후 +1 금지)
                rebuildRedisWriter.rebuildZone(zone.zoneId, zone.stock, zone.claimedOrderIds)
            }
        }
    }

    private fun notifyCompleted(
        snapshot: RebuildSnapshot,
        startedAt: Instant,
    ) {
        val durationMs = Duration.between(startedAt, Instant.now(clock)).toMillis()
        val zoneCount = snapshot.events.sumOf { it.zones.size }
        alertService.notify(
            AlertContext(
                trigger = AlertTrigger.REBUILD_COMPLETED,
                fields =
                    mapOf(
                        "events" to snapshot.events.size,
                        "zones" to zoneCount,
                        "durationMs" to durationMs,
                    ),
            ),
        )
    }
}
