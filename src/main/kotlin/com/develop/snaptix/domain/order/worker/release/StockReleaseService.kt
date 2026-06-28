package com.develop.snaptix.domain.order.worker.release

import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.global.realtime.subscribe.SseEventPublisher
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 재고 복구 공용 컴포넌트 (`#8`).
 *
 * **결제 타임아웃**(`#10 HoldExpiryWorker`)·**결제 실패**(8-2, `MockPaymentWebhookService`)
 * 두 경로에서 공통으로 호출되는 Redis 후처리 서비스.
 *
 * ### 처리 순서
 * 1. `orderId` → `ReservationRepository.findIdempotencyContextByOrderId()` 로 `userId`, `internalEventId` 조회
 * 2. `internalEventId` → `EventRepository.findById()` 로 `eventPublicId` 조회
 * 3. `StockRedisGateway.compensate()` — `+1 stock` + `SREM claimed` (Lua 원자, 이중 복구 방지)
 * 4. `IdempotencyRedisGateway.compareAndDelete()` — 값 일치 시만 DEL (soft-fail)
 * 5. `OrderHoldRedisGateway.delete()` — 멱등 DEL (soft-fail)
 * 6. `SseEventPublisher.publish()` — `PAYMENT_TIMEOUT` 또는 `ORDER_FAILED` (soft-fail)
 *
 * ### 호출자 계약
 * - **반드시** 조건부 UPDATE(`affected = 1`) 확인 후 호출한다.
 * - DB 상태 전이(RELEASED/CANCELLED)는 호출자 책임이며 이 서비스는 Redis 정리만 담당한다.
 * - Redis 장애 시: `compensate()` 실패는 예외 전파(호출자 처리), 나머지는 WARN 로그 후 계속 진행.
 */
@Service
class StockReleaseService(
    private val reservationRepository: ReservationRepository,
    private val eventRepository: EventRepository,
    private val stockRedisGateway: StockRedisGateway,
    private val idempotencyRedisGateway: IdempotencyRedisGateway,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
    private val sseEventPublisher: SseEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 재고 복구 및 Redis 후처리를 수행한다.
     *
     * @param orderId String UUID — 예약의 orderId (UUID 문자열)
     * @param zoneId  내부 zone PK — `StockRedisGateway` 키 구성에 사용
     * @param reason  실패 사유 — SSE 이벤트 타입 결정에 사용
     */
    fun release(
        orderId: String,
        zoneId: Long,
        reason: ReleaseReason,
    ) {
        val orderIdUuid = UUID.fromString(orderId)

        // 1. userId·internalEventId 조회 (payment 도메인 의존 없이 reservation 테이블에서 직접 조회)
        val context = reservationRepository.findIdempotencyContextByOrderId(orderId)
        if (context == null) {
            logger.warn { "멱등 컨텍스트 없음 — Redis 정리 부분 생략: orderId=$orderId" }
        }

        // 2. eventPublicId 조회
        val eventPublicId: UUID? =
            context?.let { ctx ->
                eventRepository
                    .findById(ctx.internalEventId)
                    ?.let { runCatching { UUID.fromString(it.publicId) }.getOrNull() }
                    ?: run {
                        logger.warn { "eventPublicId 조회 실패: internalEventId=${ctx.internalEventId}" }
                        null
                    }
            }

        // 3. +1 stock + SREM claimed (Lua 원자 — 이중 복구 방지, 예외 전파)
        val compensated = stockRedisGateway.compensate(zoneId, orderIdUuid)
        logger.debug { "재고 보상 결과: compensated=$compensated, orderId=$orderId, zoneId=$zoneId" }

        // 4. 멱등 키 compare-and-delete (soft-fail)
        if (context != null && eventPublicId != null) {
            runCatching {
                idempotencyRedisGateway.compareAndDelete(context.userId, eventPublicId, orderIdUuid)
            }.onFailure { ex ->
                logger.warn(ex) { "멱등 키 compareAndDelete 실패 (무시): orderId=$orderId" }
            }
        }

        // 5. ORDER_HOLD DEL (멱등, soft-fail)
        runCatching {
            orderHoldRedisGateway.delete(orderIdUuid)
        }.onFailure { ex ->
            logger.warn(ex) { "ORDER_HOLD DEL 실패 (무시): orderId=$orderId" }
        }

        // 6. SSE 발행 (soft-fail — 유실돼도 정합성은 MySQL이 보장)
        runCatching {
            sseEventPublisher.publish(
                key = SseChannelKey(SSE_RESOURCE, orderId),
                event =
                    SseEvent.terminal(
                        name = reason.toSseEventName(),
                        data = mapOf("orderId" to orderId),
                    ),
            )
        }.onFailure { ex ->
            logger.warn(ex) { "SSE 발행 실패 (무시): orderId=$orderId, reason=$reason" }
        }
    }

    private fun ReleaseReason.toSseEventName(): String = when (this) {
        ReleaseReason.PAYMENT_TIMEOUT -> SSE_EVENT_PAYMENT_TIMEOUT
        ReleaseReason.PAYMENT_FAILED -> SSE_EVENT_ORDER_FAILED
    }

    private companion object {
        const val SSE_RESOURCE = "order"
        const val SSE_EVENT_PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT"
        const val SSE_EVENT_ORDER_FAILED = "ORDER_FAILED"
    }
}
