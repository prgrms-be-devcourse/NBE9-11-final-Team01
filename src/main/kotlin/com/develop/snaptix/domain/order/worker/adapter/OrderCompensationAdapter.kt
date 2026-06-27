package com.develop.snaptix.domain.order.worker.adapter

import com.develop.snaptix.domain.order.worker.port.CompensationPort
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OrderCompensationAdapter(
    private val stockRedisGateway: StockRedisGateway,
) : CompensationPort {
    private val logger = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    override fun compensateIfLeaked(
        orderId: UUID,
        zoneId: Long,
    ) {
        try {
            val isCompensated = stockRedisGateway.compensate(zoneId, orderId)

            if (isCompensated) {
                logger.info { "[COMPENSATE_STOCK] Redis 보상 완료 - zoneId: $zoneId, orderId: $orderId" }
            } else {
                logger.warn { "[COMPENSATE_STOCK] Redis 보상 무시됨 (Claimed 내역 없음/이중 보상 방어) - orderId: $orderId" }
            }
        } catch (e: Exception) {
            logger.error(e) { "[COMPENSATE_STOCK_FAIL] Redis 재고 롤백 실패! 심각한 상태 불일치 가능성 - orderId: $orderId" }
        }
    }
}
