package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.domain.order.observability.LogAction
import com.develop.snaptix.domain.order.observability.OrderMetrics
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 죽은/느린 워커의 PEL 미확인 메시지를 XAUTOCLAIM으로 주기적으로 회수하여
 * [OrderProcessor]로 재투입하는 스케줄 컴포넌트. (이슈 #9, Story 3.3)
 *
 * ## 멱등 안전
 * 동시 중복 회수는 StockLuaScript의 ALREADY 분기가 흡수하고,
 * 중복 INSERT는 reservations.order_id UNIQUE 제약이 흡수한다.
 *
 * ## 트림 유실 감지
 * XAUTOCLAIM 반환의 deletedIds 가 비어있지 않으면 payload 소실(트림 유실)을 의미한다.
 * [OrderMetrics.DELETED_COUNT] 메트릭으로 Story 14.2 알람 트리거(QUEUE_TRIM_LOSS)에 활용.
 *
 * ## §9 메트릭 — 이 클래스가 책임지는 2종 + 게이지 1종
 * - [OrderMetrics.CLAIM_REPROCESS_COUNT]: XAUTOCLAIM 회수·재처리 수 (Counter)
 * - [OrderMetrics.DELETED_COUNT]: 트림 유실 수 (Counter, 0 초과 CRITICAL)
 * - [OrderMetrics.PENDING_SIZE]: 이번 주기에 회수된 PEL 메시지 수 (Gauge, 조기 신호)
 */
@Component
class OrphanReclaimer(
    private val orderStreamGateway: OrderStreamGateway,
    private val orderProcessor: OrderProcessor,
    private val activeEventDiscoveryPort: ActiveEventDiscoveryPort,
    private val meterRegistry: MeterRegistry,
    private val orderStreamProperties: OrderStreamProperties,
    @Value("\${order.consumer.claim-idle-ms:30000}") claimIdleMs: Long,
) {
    private val log = KotlinLogging.logger {}
    private val consumerId: String = buildConsumerId()
    private val minIdleTime: Duration = Duration.ofMillis(claimIdleMs)
    private val initializedGroups = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * §9 PENDING_SIZE 게이지: 마지막 XAUTOCLAIM 주기에서 회수된 PEL 메시지 수.
     * 값이 지속적으로 높으면 워커 처리 속도가 인게스트를 따라가지 못한다는 조기 신호다.
     */
    private val pendingSizeRef = AtomicLong(0)

    init {
        Gauge
            .builder(OrderMetrics.PENDING_SIZE) { pendingSizeRef.get().toDouble() }
            .description("PEL message count reclaimed in last XAUTOCLAIM cycle")
            .register(meterRegistry)
    }

    // ── 스케줄 진입점 ────────────────────────────────────────────────────────

    @LogAction("CLAIM_REPROCESS")
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedDelayString = "\${order.consumer.claim-schedule-ms:10000}")
    fun reclaimOrphans() {
        val eventIds = activeEventDiscoveryPort.getActiveEvents()
        if (eventIds.isEmpty()) return

        var totalClaimed = 0
        var totalDeleted = 0

        for (eventId in eventIds) {
            try {
                val (claimed, deleted) = claimForEvent(eventId)
                totalClaimed += claimed
                totalDeleted += deleted
            } catch (e: Exception) {
                log.atError {
                    message = "CLAIM_REPROCESS failed for event — continuing with next"
                    cause = e
                    payload = mapOf("action" to "CLAIM_REPROCESS", "eventId" to eventId)
                }
            }
        }

        // §9 PENDING_SIZE 게이지 갱신 (이번 주기 회수 총량)
        pendingSizeRef.set(totalClaimed.toLong())

        if (totalClaimed > 0 || totalDeleted > 0) {
            log.atInfo {
                message = "CLAIM_REPROCESS cycle completed"
                payload =
                    mapOf(
                        "action" to "CLAIM_REPROCESS",
                        "result" to "SUCCESS",
                        "claimedCount" to totalClaimed,
                        "deletedCount" to totalDeleted,
                    )
            }
        }
    }

    // ── 이벤트 단위 처리 ─────────────────────────────────────────────────────

    private fun claimForEvent(eventPublicId: UUID): Pair<Int, Int> {
        ensureGroupOnce(eventPublicId)

        val result =
            orderStreamGateway.claim(
                eventPublicId = eventPublicId,
                group = orderStreamProperties.consumerGroup,
                consumer = consumerId,
                minIdleTime = minIdleTime,
            )

        handleDeletedIds(eventPublicId, result.deletedIds)

        var claimedCount = 0
        for (msg in result.claimedMessages) {
            processClaimedMessage(eventPublicId, msg.id, msg.body)
            claimedCount++
        }

        if (claimedCount > 0) {
            meterRegistry.counter(OrderMetrics.CLAIM_REPROCESS_COUNT).increment(claimedCount.toDouble())
        }

        return claimedCount to result.deletedIds.size
    }

    private fun ensureGroupOnce(eventPublicId: UUID) {
        if (initializedGroups.add(eventPublicId)) {
            orderStreamGateway.ensureGroup(eventPublicId, orderStreamProperties.consumerGroup)
        }
    }

    private fun handleDeletedIds(
        eventPublicId: UUID,
        deletedIds: List<String>,
    ) {
        if (deletedIds.isEmpty()) return

        log.atError {
            message = "CRITICAL — stream trim loss detected (QUEUE_TRIM_LOSS)"
            payload =
                mapOf(
                    "action" to "CLAIM_REPROCESS",
                    "result" to "TRIM_LOSS",
                    "eventId" to eventPublicId,
                    "deletedCount" to deletedIds.size,
                    "deletedIds" to deletedIds,
                )
        }
        meterRegistry.counter(OrderMetrics.DELETED_COUNT).increment(deletedIds.size.toDouble())
    }

    // ── 메시지 단위 재처리 ───────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun processClaimedMessage(
        eventPublicId: UUID,
        messageId: String,
        body: Map<String, String>,
    ) {
        try {
            val message = OrderMessage.fromStreamPayload(body)
            log.atInfo {
                this.message = "CLAIM_REPROCESS message reprocessing"
                payload =
                    mapOf(
                        "action" to "CLAIM_REPROCESS",
                        "result" to "REPROCESSING",
                        "eventId" to eventPublicId,
                        "messageId" to messageId,
                        "orderId" to message.orderId,
                    )
            }
            orderProcessor.process(message)
            orderStreamGateway.ack(eventPublicId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: IllegalArgumentException) {
            log.atError {
                message = "CLAIM_REPROCESS terminal error — ACK to remove from PEL"
                cause = e
                payload =
                    mapOf(
                        "action" to "CLAIM_REPROCESS",
                        "result" to "TERMINAL_ERROR",
                        "messageId" to messageId,
                    )
            }
            orderStreamGateway.ack(eventPublicId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: RuntimeException) {
            log.atWarn {
                message = "CLAIM_REPROCESS non-terminal error — left in PEL"
                cause = e
                payload =
                    mapOf(
                        "action" to "CLAIM_REPROCESS",
                        "result" to "NON_TERMINAL_ERROR",
                        "messageId" to messageId,
                    )
            }
        }
    }

    private fun buildConsumerId(): String {
        val hostname =
            try {
                InetAddress.getLocalHost().hostName
            } catch (e: UnknownHostException) {
                log.warn(e) { "hostname 조회 실패, 폴백 사용" }
                "unknown-host"
            }
        return "reclaimer-$hostname-${UUID.randomUUID().toString().take(CONSUMER_ID_SUFFIX_LENGTH)}"
    }

    companion object {
        private const val CONSUMER_ID_SUFFIX_LENGTH = 6
    }
}
