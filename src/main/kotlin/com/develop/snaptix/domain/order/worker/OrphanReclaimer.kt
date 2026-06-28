package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 죽거나 느린 워커의 PEL 미확인 메시지를 XAUTOCLAIM으로 주기적으로 회수하여
 * [OrderProcessor]로 재투입하는 스케줄 컴포넌트. (이슈 #9, Story 3.3)
 *
 * ## 멱등 안전
 * 동시 중복 회수는 StockLuaScript의 ALREADY 분기가 흡수하고,
 * 중복 INSERT는 reservations.order_id UNIQUE 제약이 흡수한다.
 * 본 컴포넌트는 회수·재투입만 책임지며 처리 로직의 멱등성은 [OrderProcessor]에 위임한다.
 *
 * ## 트림 유실 감지
 * XAUTOCLAIM 반환의 deletedIds 가 비어있지 않으면 payload 소실(트림 유실)을 의미한다.
 * [METRIC_DELETED_COUNT] 메트릭을 증가시켜 Story 14.2 알람 트리거(QUEUE_TRIM_LOSS)로 활용한다.
 *
 * ## PEL 재시도
 * processClaimedMessage 에서 비터미널 예외(RuntimeException) 발생 시 XACK 를 생략한다.
 * 메시지가 PEL에 잔존하여 다음 스케줄 주기에 min-idle-time 초과 후 재회수된다.
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

    /**
     * XGROUP CREATE 는 [OrderStreamConsumer] 가 수행하지만, OrphanReclaimer 가 먼저 기동되거나
     * 독립 실행되는 경우를 대비해 그룹 생성을 한 번 보장한다. (BUSYGROUP 은 멱등 무시)
     */
    private val initializedGroups = ConcurrentHashMap.newKeySet<UUID>()

    // ── 스케줄 진입점 ────────────────────────────────────────────────────────

    /**
     * 활성 이벤트 스트림 전체를 순회하며 PEL 회수·재처리를 수행한다.
     *
     * fixedDelay 를 사용하므로 이전 실행이 완료된 후 delay 만큼 대기한 뒤 다음 실행이 시작된다.
     * 동일 인스턴스 내 중첩 실행 없음. 다중 인스턴스 간 동시 회수는 원자 Lua 가 흡수한다.
     */
    @Suppress("TooGenericExceptionCaught") // 이벤트 단위 오류를 격리하여 나머지 이벤트 처리를 보장
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
                log.error(e) { "[CLAIM_REPROCESS] eventId=$eventId 처리 중 오류, 다음 이벤트 계속" }
            }
        }

        if (totalClaimed > 0 || totalDeleted > 0) {
            log.info {
                "[CLAIM_REPROCESS] 스케줄 완료 — claimedCount=$totalClaimed, deletedCount=$totalDeleted"
            }
        }
    }

    // ── 이벤트 단위 처리 ─────────────────────────────────────────────────────

    // [Fix] CyclomaticComplexMethod: 이벤트 단위 처리를 claimForEvent 로 추출
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
            meterRegistry.counter(METRIC_REPROCESS_COUNT).increment(claimedCount.toDouble())
        }

        return claimedCount to result.deletedIds.size
    }

    /** Consumer Group 이 없으면 XAUTOCLAIM 이 오류를 반환하므로 최초 1회 생성을 보장한다. */
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

        log.error {
            "[CLAIM_REPROCESS][TRIM_LOSS] CRITICAL — 트림 유실 감지. " +
                "eventId=$eventPublicId, deletedCount=${deletedIds.size}, ids=$deletedIds"
        }
        meterRegistry.counter(METRIC_DELETED_COUNT).increment(deletedIds.size.toDouble())
    }

    // ── 메시지 단위 재처리 ───────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught") // 비터미널 에러 포착을 위해 최상위 RuntimeException 을 의도적으로 캐치함
    private fun processClaimedMessage(
        eventPublicId: UUID,
        messageId: String,
        body: Map<String, String>,
    ) {
        try {
            val message = OrderMessage.fromStreamPayload(body)
            log.info {
                "[CLAIM_REPROCESS] 메시지 재처리 시작 — " +
                    "eventId=$eventPublicId, messageId=$messageId, orderId=${message.orderId}"
            }
            orderProcessor.process(message)
            orderStreamGateway.ack(eventPublicId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: IllegalArgumentException) {
            // 페이로드 형식 오류는 터미널 오류 — 재처리 불가, ACK 후 PEL 에서 제거
            log.error(e) {
                "[CLAIM_REPROCESS][TERMINAL] 잘못된 payload 형식, ACK 처리 — messageId=$messageId"
            }
            orderStreamGateway.ack(eventPublicId, orderStreamProperties.consumerGroup, messageId)
        } catch (e: RuntimeException) {
            // 비터미널 오류 — XACK 생략, PEL 잔존하여 다음 주기에 재회수
            log.warn(e) {
                "[CLAIM_REPROCESS][NON_TERMINAL] 재처리 실패, PEL 잔존 — messageId=$messageId"
            }
        }
    }

    // ── 초기화 헬퍼 ─────────────────────────────────────────────────────────

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
        private const val METRIC_REPROCESS_COUNT = "ticketing.order.claim.reprocess.count"
        private const val METRIC_DELETED_COUNT = "ticketing.stream.deleted.count"
        private const val CONSUMER_ID_SUFFIX_LENGTH = 6
    }
}
