package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.ZoneStockInfo
import com.develop.snaptix.domain.event.repository.EventDetailZoneRecord
import com.develop.snaptix.domain.event.repository.EventListZoneRecord
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class EventStockReader(
    private val stockRedisGateway: StockRedisGateway,
    private val reservationRepository: ReservationRepository,
    private val reconcileProperties: ReconcileProperties,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 전체 zone 재고를 일괄 조회하고 장애 여부 플래그를 함께 반환한다.
     * @return stockByZoneId to useFallback
     */
    fun readStocksWithFallbackFlag(zoneIds: List<Long>): Pair<Map<Long, Int?>, Boolean> = try {
        stockRedisGateway.getAll(zoneIds) to false
    } catch (e: DataAccessException) {
        logger.warn(e) { "[EVENT_LIST_STOCK_READ_FAILED] fallback to DB" }
        emptyMap<Long, Int?>() to true
    } catch (e: RedisUnavailableException) {
        logger.warn(e) { "[EVENT_LIST_STOCK_READ_FAILED] fallback to DB" }
        emptyMap<Long, Int?>() to true
    }

    /**
     * Redis 장애 시 DB에서 zone별 occupied 수를 이벤트 단위로 일괄 산정한다.
     */
    fun buildFallbackOccupiedMap(zones: List<EventListZoneRecord>): Map<Long, Int> {
        val eventIds = zones.map { it.eventId }.distinct()
        val holdCutoff = Instant.now().minus(reconcileProperties.holdWindow)

        return eventIds
            .flatMap { eventId ->
                reservationRepository
                    .countOccupiedByZone(
                        eventId = eventId,
                        holdCutoff = holdCutoff,
                    ).entries
            }.associate { it.key to it.value }
    }

    /**
     * 상세 조회용: zone별 현재 재고를 Redis에서 읽고, 누락 시 DB 폴백으로 채운다.
     */
    fun readStockInfoList(
        eventId: Long,
        zones: List<EventDetailZoneRecord>,
    ): List<ZoneStockInfo> {
        val zoneIds = zones.map { it.id }
        val stockByZoneId = readCurrentStocks(zoneIds)
        val fallbackOccupiedByZone =
            if (stockByZoneId.hasMissingStock(zoneIds)) {
                reservationRepository.countOccupiedByZone(
                    eventId = eventId,
                    holdCutoff = Instant.now().minus(reconcileProperties.holdWindow),
                )
            } else {
                emptyMap()
            }

        return zones.map { zone ->
            zone.toStockInfo(
                currentStock = stockByZoneId[zone.id],
                fallbackOccupiedByZone = fallbackOccupiedByZone,
            )
        }
    }

    private fun readCurrentStocks(zoneIds: List<Long>): Map<Long, Int?> = try {
        stockRedisGateway.getAll(zoneIds)
    } catch (e: RedisUnavailableException) {
        logger.warn(e) { "[EVENT_DETAIL_STOCK_READ_FAILED] zoneIds=$zoneIds" }
        emptyMap()
    } catch (e: DataAccessException) {
        logger.warn(e) { "[EVENT_DETAIL_STOCK_READ_FAILED] zoneIds=$zoneIds" }
        emptyMap()
    }

    private fun Map<Long, Int?>.hasMissingStock(zoneIds: List<Long>): Boolean = zoneIds.any { this[it] == null }

    private fun EventDetailZoneRecord.toStockInfo(
        currentStock: Int?,
        fallbackOccupiedByZone: Map<Long, Int>,
    ): ZoneStockInfo {
        val fallbackStock =
            (totalCapacity - fallbackOccupiedByZone.getOrDefault(id, 0))
                .coerceAtLeast(0)
        return ZoneStockInfo(
            zoneId = publicId,
            name = name,
            unitPrice = unitPrice,
            totalCapacity = totalCapacity,
            currentStock = currentStock ?: fallbackStock,
        )
    }
}
