package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.config.EventCleanupProperties
import com.develop.snaptix.domain.event.repository.EventCleanupCandidate
import com.develop.snaptix.domain.event.repository.EventKeyCleanupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * CLOSED 이벤트 고아 Redis 키 정리 스윕. (명세서: 백스톱 — 스윕 + TTL)
 *
 * 후보(이벤트+zoneIds)는 [com.develop.snaptix.domain.event.repository.EventKeyCleanupRepository]가 **단일 트랜잭션**으로 읽고,
 * Redis 정리는 **트랜잭션 밖에서** 이벤트 단위로 [EventRedisKeyCleaner.cleanup] 재호출(DEL/EXPIRE 멱등).
 * 한 이벤트 실패가 배치를 막지 않도록 격리하고 [CleanupReport]로 집계한다.
 */
@Service
class EventKeyCleanupService(
    private val eventKeyCleanupRepository: EventKeyCleanupRepository,
    private val eventRedisKeyCleaner: EventRedisKeyCleaner,
    private val eventCleanupProperties: EventCleanupProperties,
) {
    private val logger = KotlinLogging.logger {}

    fun sweep(now: Instant): CleanupReport {
        val cutoff = now.minus(eventCleanupProperties.window)
        val candidates = eventKeyCleanupRepository.findClosedCleanupTargets(cutoff)

        val acc = CleanupReport.Accumulator()
        candidates.forEach { cleanupSafely(it, acc) }

        return acc.toReport().also { report ->
            logger.atInfo {
                message = "Event key cleanup sweep done"
                payload = report.asLogPayload() + ("action" to "EVENT_KEY_CLEANUP_SWEEP")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 이벤트 단위 격리: 한 건 실패가 배치를 막지 않도록
    private fun cleanupSafely(
        candidate: EventCleanupCandidate,
        acc: CleanupReport.Accumulator,
    ) {
        try {
            val deleted =
                eventRedisKeyCleaner.cleanup(
                    EventRedisCleanupTarget(
                        eventPublicId = candidate.eventPublicId,
                        zoneIds = candidate.zoneIds,
                    ),
                )
            if (deleted > 0L) acc.cleaned++ else acc.skipped++
        } catch (e: Exception) {
            // runCatching 미사용: Error 전파
            acc.failed++
            logger.atError {
                message = "Event key cleanup failed for one event (retried next sweep)"
                cause = e
                payload = mapOf("eventPublicId" to candidate.eventPublicId)
            }
        }
    }
}
