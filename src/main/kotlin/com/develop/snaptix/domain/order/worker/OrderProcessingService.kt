package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.domain.order.worker.port.CompensationPort
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.domain.reservation.repository.ReservationView
import com.develop.snaptix.global.realtime.SseChannelKey
import com.develop.snaptix.global.realtime.SseConnectionManager
import com.develop.snaptix.global.realtime.SseEvent
import com.develop.snaptix.global.redis.gateway.DecreaseResult
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderHoldRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 워커 주문 처리 서비스 — [OrderProcessor] 프로덕션 구현체. (#6a happy-path)
 *
 * ## 처리 흐름 (순서 고정)
 * ```
 * 1. 멱등 선검사   findByOrderId → 행 존재 시 상태별 SSE 재발행 후 즉시 반환(재차감 없음)
 * 2. eventId 변환  findInternalEventId(zoneId) → internal Long (A안: ZonesTable 역참조)
 * 3. 1인1매 선검사 existsActiveForUserAndEvent → 중복 시 ORDER_FAILED 발행 후 즉시 반환
 * 4. Redis 권위 관문 decreaseAndClaim →
 *      SOLD_OUT  : ORDER_FAILED 발행 후 반환
 *      ALREADY   : 재배달+DB행 없음 엣지케이스 → 비터미널 예외로 PEL 잔존
 *      OK        : 5번으로 진행
 * 5. DB INSERT    insertPending (Exposed transaction)
 *      실패       : compensateIfLeaked → 예외 재전파(비터미널, PEL 잔존)
 *      성공       : 6번으로 진행
 * 6. 사후 처리    ORDER_HOLD SET + 멱등 키 재앵커링 + READY_TO_PAY 발행 (best-effort)
 * ```
 *
 * ## 트랜잭션 경계
 * DB INSERT([ReservationRepository.insertPending])만 Exposed `transaction {}`으로 감싸며,
 * 모든 Redis 작업은 트랜잭션 **밖**에서 수행한다. INSERT 실패 시 보상([CompensationPort])을
 * 먼저 호출한 뒤 예외를 재전파해 메시지를 PEL에 남긴다.
 *
 * ## ACK 계약 ([OrderStreamConsumer]와의 인터페이스)
 * - 정상 반환       → 소비자가 XACK (성공/터미널 실패 모두 포함)
 * - RuntimeException → 소비자가 XACK 안 함 → PEL 잔존 → OrphanReclaimer(#9)가 재처리
 * - IllegalArgumentException → 소비자가 XACK (터미널: 페이로드 불량·데이터 정합 이상)
 *
 * ## #6b 범위 제외
 * DB INSERT 제약 위반(`uk_active_user_event`, `orderId` UNIQUE) 분기는 #6b에서 처리한다.
 * 현재 `insertPending` 예외는 모두 비터미널(보상 후 재전파)로 처리된다.
 *
 * @Profile("!local") — [StubOrderProcessorAdapter](@Profile("local"))와 빈 경합 차단.
 */
@Component
@Profile("!local")
class OrderProcessingService(
    private val stockRedisGateway: StockRedisGateway,
    private val reservationRepository: ReservationRepository,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
    private val idempotencyRedisGateway: IdempotencyRedisGateway,
    private val sseConnectionManager: SseConnectionManager,
    private val compensationPort: CompensationPort,
) : OrderProcessor {
    private val log = KotlinLogging.logger {}

    // ── OrderProcessor 구현 ─────────────────────────────────────────────

    override fun process(message: OrderMessage) {
        // ① 멱등 선검사: 이미 DB 행이 있으면 재배달 처리 후 즉시 반환
        val existing = reservationRepository.findByOrderId(message.orderId.toString())
        if (existing != null) {
            log.info { "[REDELIVERY] DB 행 존재 → SSE 재발행. orderId=${message.orderId}" }
            handleRedelivery(message.orderId, existing)
            return
        }

        // ② eventId 변환 (A안: zoneId → 내부 eventId Long)
        val internalEventId =
            reservationRepository.findInternalEventId(message.zoneId)
                ?: throw IllegalArgumentException(
                    "[TERMINAL] zoneId=${message.zoneId}에 해당하는 event 없음. " +
                        "orderId=${message.orderId}",
                )

        // ③ 1인1매 선검사: 이미 유효 점유가 있으면 ORDER_FAILED 후 즉시 반환
        if (reservationRepository.existsActiveForUserAndEvent(message.userId, internalEventId)) {
            log.warn {
                "[DUPLICATE_ACTIVE] userId=${message.userId}, eventId=$internalEventId 이미 유효 점유 존재. " +
                    "orderId=${message.orderId}"
            }
            publishOrderFailed(message.orderId, REASON_DUPLICATE_ACTIVE)
            return
        }

        // ④ Redis 권위 관문 (원자 Lua: SISMEMBER → GET → DECR → SADD)
        when (stockRedisGateway.decreaseAndClaim(message.zoneId, message.orderId)) {
            DecreaseResult.SOLD_OUT -> {
                log.info { "[SOLD_OUT] zoneId=${message.zoneId}, orderId=${message.orderId}" }
                publishOrderFailed(message.orderId, REASON_SOLD_OUT)
            }

            DecreaseResult.ALREADY -> {
                // claimed에는 있으나 DB 행이 없는 엣지케이스:
                // 이전 워커가 차감 후 INSERT 진행 중이거나 INSERT 실패 후 보상 미완료.
                // → 비터미널 예외로 PEL 잔존. OrphanReclaimer(#9)가 idle 초과 시 재처리.
                throw IllegalStateException(
                    "[NON_TERMINAL] Lua=ALREADY이나 DB 행 없음. 이전 워커 처리 중 또는 보상 미완료. " +
                        "orderId=${message.orderId}",
                )
            }

            DecreaseResult.OK -> {
                log.info { "[DECREASE_OK] zoneId=${message.zoneId}, orderId=${message.orderId}" }
                insertAndPublish(message, internalEventId)
            }
        }
    }

    // ── 재배달 처리 ────────────────────────────────────────────────────

    /**
     * DB 행이 이미 있는 재배달 메시지 처리.
     * 상태에 맞는 SSE 이벤트를 재발행해 클라이언트가 최신 상태를 받도록 한다.
     * Redis 차감을 일절 수행하지 않는다.
     */
    private fun handleRedelivery(
        orderId: UUID,
        existing: ReservationView,
    ) {
        val data = buildSseData(orderId, existing.status.name)
        val key = orderSseKey(orderId)

        when (existing.status) {
            ReservationStatus.PENDING_PAYMENT ->
                sseConnectionManager.dispatch(key, SseEvent.ongoing(EVENT_READY_TO_PAY, data))

            ReservationStatus.CONFIRMED ->
                sseConnectionManager.dispatch(key, SseEvent.terminal(EVENT_TICKET_ISSUED, data))

            ReservationStatus.CANCELLED, ReservationStatus.RELEASED ->
                sseConnectionManager.dispatch(key, SseEvent.terminal(EVENT_ORDER_FAILED, data))
        }
    }

    // ── INSERT + 사후 처리 ─────────────────────────────────────────────

    /**
     * PENDING_PAYMENT INSERT 후 ORDER_HOLD·멱등키·SSE 사후 처리.
     *
     * INSERT 실패 시: [CompensationPort.compensateIfLeaked] → 예외 재전파(PEL 잔존).
     * INSERT 성공 후 Redis/SSE 실패 시: 로그 후 계속(best-effort). 클라이언트는 폴링(#13)으로 보완.
     */
    private fun insertAndPublish(
        message: OrderMessage,
        internalEventId: Long,
    ) {
        // ⑤ DB INSERT — 실패 시 보상 후 비터미널 예외 재전파
        try {
            reservationRepository.insertPending(
                orderId = message.orderId.toString(),
                userId = message.userId,
                internalEventId = internalEventId,
                zoneId = message.zoneId,
            )
        } catch (e: ExposedSQLException) {
            // Exposed native transaction {}의 모든 DB 오류는 ExposedSQLException으로 래핑된다.
            // #6b에서 e.cause(SQLException) → 제약명 분기(orderId UNIQUE / uk_active_user_event)로 세분화된다.
            compensationPort.compensateIfLeaked(message.orderId, message.zoneId)
            log.error(e) {
                "[INSERT_FAILED] Redis 보상 요청. orderId=${message.orderId}, zoneId=${message.zoneId}"
            }
            throw e
        }

        log.info { "[INSERT_OK] PENDING_PAYMENT 생성. orderId=${message.orderId}" }

        // ⑥ 사후 처리 — best-effort (실패해도 XACK 진행, 클라이언트는 폴링으로 보완)
        val data = buildSseData(message.orderId, ReservationStatus.PENDING_PAYMENT.name)

        runCatching { orderHoldRedisGateway.create(message.orderId) }
            .onFailure {
                log.error(it) { "[ORDER_HOLD_FAIL] best-effort 실패. orderId=${message.orderId}" }
            }

        runCatching { idempotencyRedisGateway.reanchor(message.userId, message.eventId) }
            .onFailure {
                log.error(it) {
                    "[IDEMPOTENCY_REANCHOR_FAIL] best-effort 실패. orderId=${message.orderId}"
                }
            }

        runCatching {
            sseConnectionManager.dispatch(
                orderSseKey(message.orderId),
                SseEvent.ongoing(EVENT_READY_TO_PAY, data),
            )
        }.onFailure {
            log.warn(it) { "[SSE_DISPATCH_FAIL] orderId=${message.orderId}" }
        }
    }

    // ── ORDER_FAILED 공통 발행 ─────────────────────────────────────────

    private fun publishOrderFailed(
        orderId: UUID,
        reason: String,
    ) {
        val data = buildSseData(orderId, reason)
        runCatching {
            sseConnectionManager.dispatch(orderSseKey(orderId), SseEvent.terminal(EVENT_ORDER_FAILED, data))
        }.onFailure {
            log.warn(it) { "[SSE_ORDER_FAILED_FAIL] orderId=$orderId, reason=$reason" }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────

    private fun orderSseKey(orderId: UUID) = SseChannelKey(resource = SSE_RESOURCE, id = orderId.toString())

    private fun buildSseData(
        orderId: UUID,
        statusOrReason: String,
    ): Map<String, String> = mapOf("orderId" to orderId.toString(), "status" to statusOrReason)

    // ── 상수 ──────────────────────────────────────────────────────────

    companion object {
        private const val SSE_RESOURCE = "order"

        // SSE 이벤트명 (OrderSseAdapter 상수와 동기화)
        private const val EVENT_READY_TO_PAY = "READY_TO_PAY"
        private const val EVENT_ORDER_FAILED = "ORDER_FAILED"
        private const val EVENT_TICKET_ISSUED = "TICKET_ISSUED"

        // ORDER_FAILED reason 코드
        private const val REASON_SOLD_OUT = "SOLD_OUT"
        private const val REASON_DUPLICATE_ACTIVE = "DUPLICATE_ACTIVE"
    }
}
