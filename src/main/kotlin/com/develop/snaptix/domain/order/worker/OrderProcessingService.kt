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
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 워커 주문 처리 서비스 — [OrderProcessor] 실 구현체.
 *
 * 명세서 6번 파이프라인 (Happy-path 및 6-b DB 제약 위반 분기) 연동 완벽 수정본.
 */
@Component
class OrderProcessingService(
    private val reservationRepository: ReservationRepository,
    private val stockRedisGateway: StockRedisGateway,
    private val orderHoldRedisGateway: OrderHoldRedisGateway,
    private val idempotencyRedisGateway: IdempotencyRedisGateway,
    private val sseConnectionManager: SseConnectionManager,
    private val compensationPort: CompensationPort, // 6-b 연동 보상 포트
) : OrderProcessor {
    private val log = KotlinLogging.logger {}

    override fun process(message: OrderMessage) {
        val orderId = message.orderId
        val userId = message.userId

        log.info { "[ORDER_WORKER_START] 주문 처리 파이프라인 시작 - orderId=$orderId, userId=$userId" }

        val existingReservation = reservationRepository.findByOrderId(orderId.toString())
        if (existingReservation != null) {
            log.info {
                "[ORDER_WORKER_IDEMPTENT] DB에 이미 예약 행이 존재합니다. 재배달 우회 처리 " +
                    "- orderId=$orderId, status=${existingReservation.status}"
            }
            republishSseByStatus(orderId, existingReservation)
            return
        }

        validateAndProcess(message)
    }

    private fun validateAndProcess(message: OrderMessage) {
        val orderId = message.orderId
        val userId = message.userId
        val zoneId = message.zoneId

        val internalEventId =
            reservationRepository.findInternalEventId(zoneId) ?: run {
                log.error { "[ORDER_WORKER_CRITICAL] Zone PK 역산 실패 - zoneId=$zoneId, orderId=$orderId" }
                publishOrderFailed(orderId, "유효하지 않은 구역 정보입니다.")
                return
            }

        if (reservationRepository.existsActiveForUserAndEvent(userId, internalEventId)) {
            log.warn { "[ORDER_WORKER_DUPLICATE_PRECHECK] 1인 1매 사전 차단 - userId=$userId" }
            publishOrderFailed(orderId, "이미 동일한 이벤트에 유효한 점유 내역이 존재합니다.")
            return
        }

        executeOrder(message, internalEventId)
    }

    private fun executeOrder(
        message: OrderMessage,
        internalEventId: Long,
    ) {
        val orderId = message.orderId
        val zoneId = message.zoneId

        when (stockRedisGateway.decreaseAndClaim(zoneId, orderId)) {
            DecreaseResult.SOLD_OUT -> {
                log.info { "[ORDER_WORKER_SOLD_OUT] Redis 재고 소진 - zoneId=$zoneId, orderId=$orderId" }
                publishOrderFailed(orderId, "선택하신 구역의 좌석이 매진되었습니다.")
                return
            }
            DecreaseResult.ALREADY -> {
                log.info { "[ORDER_WORKER_ALREADY_CLAIMED] Redis 관문상 이미 차감된 내역 감지 - orderId=$orderId" }
                throw IllegalStateException("Redis 차감은 성공했으나 DB 영속화가 누락된 경계 상태입니다. 재시도를 유도합니다.")
            }
            DecreaseResult.OK -> {
                log.info { "[ORDER_WORKER_STOCK_CLAIMED] Redis 관문 원자적 차감 성공 - zoneId=$zoneId, orderId=$orderId" }
                insertAndPublish(message, internalEventId)
            }
        }
    }

    // ── 멱등 재배달용 SSE 재발행 서브 라우터 ───────────────────────────────────

    private fun republishSseByStatus(
        orderId: UUID,
        view: ReservationView,
    ) {
        val status = view.status
        val data = buildSseData(orderId, status.name)
        val channelKey = orderSseKey(orderId)

        when (status) {
            ReservationStatus.PENDING_PAYMENT -> {
                // 아직 결제 대기 중이면 기존 약격에 맞게 ongoing 유지
                sseConnectionManager.dispatch(channelKey, SseEvent.ongoing(EVENT_READY_TO_PAY, data))
            }
            ReservationStatus.CANCELLED, ReservationStatus.RELEASED -> {
                // 이미 실패 처리된 건이면 terminal 발송
                sseConnectionManager.dispatch(
                    channelKey,
                    SseEvent.terminal(EVENT_ORDER_FAILED, buildSseData(orderId, "취소되거나 만료된 주문입니다.")),
                )
            }
            ReservationStatus.CONFIRMED -> {
                // 결제까지 끝난 상태면 도메인 규칙상의 터미널 신호 전송
                sseConnectionManager.dispatch(channelKey, SseEvent.terminal(EVENT_TICKET_ISSUED, data))
            }
        }
    }

    // ── INSERT + 사후 처리 ─────────────────────────────────────────────

    /**
     * PENDING_PAYMENT INSERT 후 ORDER_HOLD·멱등키·SSE 사후 처리.
     *
     * INSERT 실패 시: [CompensationPort.compensateIfLeaked] → 예외 재전파(PEL 잔존).
     * INSERT 성공 후 Redis/SSE 실패 시: 로그 후 계속(best-effort). 클라이언트는 폴링(#13)으로 보완.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun insertAndPublish(
        message: OrderMessage,
        internalEventId: Long,
    ) {
        val orderId = message.orderId
        val userId = message.userId
        val eventPublicId = message.eventId
        val zoneId = message.zoneId

        // ── 5단계: DB Insert 및 예외 전파 (6-b 보완 반영) ─────────────────────
        try {
            reservationRepository.insertPending(
                orderId = orderId.toString(),
                userId = userId,
                internalEventId = internalEventId,
                zoneId = zoneId,
            )
            log.info { "[ORDER_WORKER_DB_INSERT] DB PENDING_PAYMENT 예약 영속화 성공 - orderId=$orderId" }
        } catch (e: ExposedSQLException) {
            log.error(e) { "[ORDER_WORKER_DB_FAIL] DB Insert 실패 - orderId=$orderId" }

            when (extractConstraintName(e)) {
                CONSTRAINT_ORDER_ID -> {
                    // A. 흡수 — 보상 없음. row가 이미 존재하므로 Redis claimed 유지
                    log.warn { "[ORDER_WORKER_ABSORB] order_id 재배달 흡수 - orderId=$orderId" }
                    val existing = reservationRepository.findByOrderId(orderId.toString())
                    if (existing != null) {
                        republishSseByStatus(orderId, existing)
                    } else {
                        publishOrderFailed(orderId, "이미 처리된 주문입니다.")
                    }
                    return // ACK
                }
                CONSTRAINT_ACTIVE_USER_EVENT -> {
                    // B. 1인 1매 위반 — 보상 + ORDER_FAILED
                    log.warn {
                        "[ORDER_WORKER_DUPLICATE_ACTIVE] uk_active_user_event 위반 - userId=$userId, orderId=$orderId"
                    }
                    compensationPort.compensateIfLeaked(orderId, zoneId)
                    publishOrderFailed(orderId, "이미 동일 이벤트에 유효한 점유 내역이 존재합니다.")
                    return // ACK
                }
                else -> {
                    // C. 알 수 없는 제약 or 인프라 오류 — 보상 후 재전파 (PEL 잔존)
                    log.error { "[ORDER_WORKER_UNKNOWN_CONSTRAINT] 알 수 없는 DB 오류, 재시도 유도 - orderId=$orderId" }
                    compensationPort.compensateIfLeaked(orderId, zoneId)
                    throw e // Consumer가 PEL에 잔존시킴
                }
            }
        } catch (e: RuntimeException) {
            // 인프라 오류 (deadlock, timeout 등)
            compensationPort.compensateIfLeaked(orderId, zoneId)
            throw e
        }

        // ── 6단계: 사후 처리 (Post-Processing) ──────────────────────────────
        // Happy-path 진입에 성공했으므로 개별 잡 실패로 인한 오염 가드(`runCatching`)를 적용해 견고함 유지
        log.info { "[ORDER_WORKER_POST_START] 파이프라인 최종 6단계 사후 처리 진입 - orderId=$orderId" }

        runCatching {
            orderHoldRedisGateway.create(orderId)
        }.onFailure { log.error(it) { "[ORDER_WORKER_HOLD_FAIL] ORDER_HOLD 생성 누수 발생 - orderId=$orderId" } }

        runCatching {
            idempotencyRedisGateway.reanchor(userId, eventPublicId)
        }.onFailure { log.error(it) { "[ORDER_WORKER_REANCHOR_FAIL] 멱등 키 5분 재앵커링 누수 발생 - userId=$userId" } }

        runCatching {
            // SseEvent.ongoing 팩토리 호출 규격 준수 (연결 유지형)
            val data = buildSseData(orderId, EVENT_READY_TO_PAY)
            sseConnectionManager.dispatch(orderSseKey(orderId), SseEvent.ongoing(EVENT_READY_TO_PAY, data))
            log.info { "[ORDER_WORKER_SUCCESS] READY_TO_PAY SSE 발행 완료 - orderId=$orderId" }
        }.onFailure { log.warn(it) { "[ORDER_WORKER_SSE_FAIL] READY_TO_PAY SSE 발송 누수 발생 - orderId=$orderId" } }
    }

    // ── ORDER_FAILED 공통 발행 (SseEvent.terminal 규칙 완벽 동기화) ──────────

    private fun publishOrderFailed(
        orderId: UUID,
        reason: String,
    ) {
        val data = buildSseData(orderId, reason)
        runCatching {
            // 프로젝트 팩토리 메서드인 SseEvent.terminal 사용
            sseConnectionManager.dispatch(orderSseKey(orderId), SseEvent.terminal(EVENT_ORDER_FAILED, data))
        }.onFailure {
            log.warn(it) { "[SSE_ORDER_FAILED_FAIL] orderId=$orderId, reason=$reason" }
        }
    }

    // ── 구조 정합 헬퍼 함수 ──────────────────────────────────────────────────

    private fun orderSseKey(orderId: UUID): SseChannelKey {
        // SseChannelKey 주 생성자 규격(resource, id) 일치화 완료
        return SseChannelKey(resource = SSE_RESOURCE, id = orderId.toString())
    }

    private fun buildSseData(
        orderId: UUID,
        statusOrReason: String,
    ): Map<String, String> = mapOf("orderId" to orderId.toString(), "status" to statusOrReason)

    private fun extractConstraintName(e: ExposedSQLException): String? = e.message?.let { msg ->
        listOf(CONSTRAINT_ORDER_ID, CONSTRAINT_ACTIVE_USER_EVENT)
            .firstOrNull { msg.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val SSE_RESOURCE = "order"
        private const val EVENT_READY_TO_PAY = "READY_TO_PAY"
        private const val EVENT_ORDER_FAILED = "ORDER_FAILED"
        private const val EVENT_TICKET_ISSUED = "TICKET_ISSUED"

        private const val CONSTRAINT_ORDER_ID = "PRIMARY"
        private const val CONSTRAINT_ACTIVE_USER_EVENT = "uk_active_user_event"
    }
}
