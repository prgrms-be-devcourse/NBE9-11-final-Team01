package com.develop.snaptix.global.resilience

import com.develop.snaptix.domain.reservation.repository.RebuildSnapshot
import com.develop.snaptix.domain.reservation.repository.RebuildSnapshotRepository
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 재구축 스냅샷 진입점. (작업 명세서 v2.1 §7 · Story 13.2)
 *
 * 데이터 접근은 [RebuildSnapshotRepository](단일 트랜잭션·batched 쿼리)에 위임하고, 본 컴포넌트는
 * `now` → `holdCutoff` 변환(계약 #0: 시계 고정)만 책임진다.
 */
@Component
class RebuildSnapshotReader(
    private val rebuildSnapshotRepository: RebuildSnapshotRepository,
    private val reconcileProperties: ReconcileProperties,
) {
    fun read(now: Instant): RebuildSnapshot = rebuildSnapshotRepository.read(now.minus(reconcileProperties.holdWindow))
}
