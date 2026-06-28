package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 결제 확정(CONFIRMED) 이후 Redis 후처리 서비스.
 *
 * FAIL 경로([StockReleaseService])와 달리 재고 반납이 없고 좌석이 점유 상태를 유지하므로
 * `claimed SREM only`(+1 없음) + 멱등 키 COMPLETED 마킹 + ORDER_HOLD DEL 만 수행한다.
 *
 * > SSE `TICKET_ISSUED` 발행은 [com.develop.snaptix.domain.ticket.service.TicketService.issue] 로
 * > 이관됐다. 이 서비스는 Redis 후처리만 담당한다.
 *
 * ### 처리 순서
 * 1. `EventRepository.findById(internalEventId)` → `eventPublicId` 획득
 * 2. `StockRedisGateway.removeClaimed()` — `SREM claimed` only, **+1 없음** (soft-fail)
 * 3. `IdempotencyRedisGateway.markCompleted()` — `SET COMPLETED KEEPTTL` (soft-fail)
 * 4. `OrderHoldRedisGateway.delete()` — 멱등 DEL (soft-fail)
 *
 * ### 호출자 계약
 * - `confirmIfPending()` → `processed = true` 확인 후 호출한다.
 * - 모든 Redis 작업은 soft-fail이므로 이 서비스의 실패가 결제 확정 응답에 영향을 주지 않는다.
 */
@Service
class PaymentConfirmRedisCleanupService(
    private val eventRepository: EventRepository,
    private val stockRedisGateway: StockRedisGateway,
    private val idempotencyRedisGateway: IdempotencyRedisGateway,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 결제 확정 이후 Redis 후처리를 수행한다.
     *
     * @param orderId         주문 UUID
     * @param zoneId          내부 zone PK
     * @param userId          내부 user PK
     * @param internalEventId 내부 event PK (EventRepository 조회용)
     */
    fun cleanup(
        orderId: UUID,
        zoneId: Long,
        userId: Long,
        internalEventId: Long,
    ) {
        // 1. eventPublicId 조회 (markCompleted 구성에 필요)
        val eventPublicId: UUID? =
            eventRepository
                .findById(internalEventId)
                ?.let { runCatching { UUID.fromString(it.publicId) }.getOrNull() }
                ?: run {
                    logger.warn {
                        "eventPublicId 조회 실패 — markCompleted 생략: internalEventId=$internalEventId, orderId=$orderId"
                    }
                    null
                }

        // 2. claimed SREM only — 재고는 반납하지 않는다 (soft-fail)
        runCatching {
            stockRedisGateway.removeClaimed(zoneId, orderId)
        }.onFailure { ex ->
            logger.warn(ex) { "claimed SREM 실패 (무시): orderId=$orderId, zoneId=$zoneId" }
        }

        // 3. 멱등 키 → COMPLETED (SET KEEPTTL, soft-fail)
        if (eventPublicId != null) {
            runCatching {
                idempotencyRedisGateway.markCompleted(userId, eventPublicId)
            }.onFailure { ex ->
                logger.warn(ex) { "멱등 키 markCompleted 실패 (무시): orderId=$orderId" }
            }
        }

        // 4. ORDER_HOLD DEL (멱등, soft-fail)
        runCatching {
            orderHoldRedisGateway.delete(orderId)
        }.onFailure { ex ->
            logger.warn(ex) { "ORDER_HOLD DEL 실패 (무시): orderId=$orderId" }
        }
    }
}
