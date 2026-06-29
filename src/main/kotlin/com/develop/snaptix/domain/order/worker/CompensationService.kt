package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.order.worker.port.CompensationPort
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 재고 보상 불변식 포트 구현체.
 * 기존 OrderCompensationAdapter를 승격 및 대체합니다.
 * * [이슈 #7] 커밋된 DB 행 없음 재조회 가드를 추가하여 워커 크래시 및 PEL 재배달 시
 * 발생할 수 있는 재고 오버카운트 엣지 케이스를 방지합니다.
 */
@Service
class CompensationService(
    private val stockRedisGateway: StockRedisGateway,
    private val reservationRepository: ReservationRepository,
) : CompensationPort {
    private val log = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    override fun compensateIfLeaked(
        orderId: UUID,
        zoneId: Long,
    ) {
        try {
            // Step 1: Claimed 멤버십 선검사 (SISMEMBER)
            // Redis에 차감된 이력이 없다면 DB를 조회할 필요 없이 즉시 no-op 반환
            if (!stockRedisGateway.isClaimed(zoneId, orderId)) {
                log.debug { "[COMPENSATION_SKIP] Redis Claimed 멤버십에 존재하지 않음 (보상 불필요) - orderId: $orderId" }
                return
            }

            // Step 2: DB 재조회 가드 (커밋된 DB 행 유무 확인)
            // 보상 직전에 재조회하여 워커 크래시 후 PEL 재배달로 인한 이중 보상(재고 오버카운트) 방지
            val existingReservation = reservationRepository.findByOrderId(orderId.toString())
            if (existingReservation != null) {
                log.warn {
                    "[COMPENSATION_SKIP] DB에 이미 커밋된 예약 행이 존재하여 보상을 스킵합니다. " +
                        "(이중 보상 방어) - orderId: $orderId, status: ${existingReservation.status}"
                }
                return
            }

            // Step 3: Lua 원자 보상 실행 (INCRBY 1 + SREM orderId)
            // 행이 없을 때만 원자적으로 복구 실행
            val isCompensated = stockRedisGateway.compensate(zoneId, orderId)

            if (isCompensated) {
                log.info { "[COMPENSATE_STOCK_SUCCESS] Redis 보상 완료 (+1, SREM) - zoneId: $zoneId, orderId: $orderId" }
            } else {
                // 선검사를 통과했으나 그 사이 Lua 스크립트 내에서 SISMEMBER가 0으로 판별된 경우
                log.warn { "[COMPENSATE_STOCK_IGNORED] Redis 보상 무시됨 (경합 중 이미 처리됨) - orderId: $orderId" }
            }
        } catch (e: Exception) {
            // Redis 인프라 장애 시 예외를 흡수하여 상위 플로우(OrderProcessingService) 오염 방지
            log.error(e) { "[COMPENSATE_STOCK_FAIL] Redis 재고 롤백 실패! 심각한 상태 불일치 가능성 - orderId: $orderId" }
        }
    }
}
