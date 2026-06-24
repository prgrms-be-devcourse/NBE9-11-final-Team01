package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.repository.EventDetailZoneRecord
import com.develop.snaptix.domain.event.repository.EventListZoneRecord
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.resilience.ReconcileProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import java.util.UUID

class EventStockReaderTest {
    private val stockRedisGateway = mockk<StockRedisGateway>()
    private val reservationRepository = mockk<ReservationRepository>()
    private val reconcileProperties = ReconcileProperties()

    private val eventStockReader =
        EventStockReader(
            stockRedisGateway = stockRedisGateway,
            reservationRepository = reservationRepository,
            reconcileProperties = reconcileProperties,
        )

    // ────────────────────────────────────────────────
    // readStocksWithFallbackFlag
    // ────────────────────────────────────────────────

    @Test
    fun `Redis 정상 시 재고 맵과 useFallback=false를 반환한다`() {
        every { stockRedisGateway.getAll(listOf(10L, 11L)) } returns mapOf(10L to 5, 11L to 0)

        val (stocks, useFallback) = eventStockReader.readStocksWithFallbackFlag(listOf(10L, 11L))

        assertThat(stocks).containsEntry(10L, 5).containsEntry(11L, 0)
        assertThat(useFallback).isFalse()
    }

    @Test
    fun `RedisUnavailableException 발생 시 빈 맵과 useFallback=true를 반환한다`() {
        every { stockRedisGateway.getAll(any()) } throws RedisUnavailableException()

        val (stocks, useFallback) = eventStockReader.readStocksWithFallbackFlag(listOf(10L))

        assertThat(stocks).isEmpty()
        assertThat(useFallback).isTrue()
    }

    @Test
    fun `DataAccessException 발생 시 빈 맵과 useFallback=true를 반환한다`() {
        every { stockRedisGateway.getAll(any()) } throws RedisConnectionFailureException("redis down")

        val (stocks, useFallback) = eventStockReader.readStocksWithFallbackFlag(listOf(10L))

        assertThat(stocks).isEmpty()
        assertThat(useFallback).isTrue()
    }

    @Test
    fun `zone이 없으면 빈 맵과 useFallback=false를 반환한다`() {
        every { stockRedisGateway.getAll(emptyList()) } returns emptyMap()

        val (stocks, useFallback) = eventStockReader.readStocksWithFallbackFlag(emptyList())

        assertThat(stocks).isEmpty()
        assertThat(useFallback).isFalse()
    }

    // ────────────────────────────────────────────────
    // buildFallbackOccupiedMap
    // ────────────────────────────────────────────────

    @Test
    fun `이벤트별로 countOccupiedByZone을 한 번씩만 호출한다`() {
        val zones =
            listOf(
                zoneListRecord(eventId = 1L, zoneId = 10L),
                zoneListRecord(eventId = 1L, zoneId = 11L),
                zoneListRecord(eventId = 2L, zoneId = 20L),
            )
        every { reservationRepository.countOccupiedByZone(eq(1L), any()) } returns mapOf(10L to 80, 11L to 100)
        every { reservationRepository.countOccupiedByZone(eq(2L), any()) } returns mapOf(20L to 50)

        val result = eventStockReader.buildFallbackOccupiedMap(zones)

        assertThat(result).containsEntry(10L, 80).containsEntry(11L, 100).containsEntry(20L, 50)
        // 이벤트 수(2)만큼만 호출 — N+1 방지
        verify(exactly = 2) { reservationRepository.countOccupiedByZone(any(), any()) }
    }

    @Test
    fun `점유가 없는 zone은 결과 맵에 포함되지 않는다`() {
        val zones = listOf(zoneListRecord(eventId = 1L, zoneId = 10L))
        every { reservationRepository.countOccupiedByZone(eq(1L), any()) } returns emptyMap()

        val result = eventStockReader.buildFallbackOccupiedMap(zones)

        assertThat(result).isEmpty()
    }

    // ────────────────────────────────────────────────
    // readStockInfoList
    // ────────────────────────────────────────────────

    @Test
    fun `Redis에 재고가 모두 있으면 DB fallback을 호출하지 않는다`() {
        val zones =
            listOf(
                detailZoneRecord(id = 10L, totalCapacity = 100),
                detailZoneRecord(id = 11L, totalCapacity = 200),
            )
        every { stockRedisGateway.getAll(listOf(10L, 11L)) } returns mapOf(10L to 57, 11L to 0)

        val result = eventStockReader.readStockInfoList(eventId = 1L, zones = zones)

        assertThat(result.map { it.currentStock }).containsExactly(57, 0)
        verify(exactly = 0) { reservationRepository.countOccupiedByZone(any(), any()) }
    }

    @Test
    fun `Redis에 일부 재고가 없으면 DB fallback으로 해당 zone 재고를 채운다`() {
        val zones =
            listOf(
                detailZoneRecord(id = 10L, totalCapacity = 100),
                detailZoneRecord(id = 11L, totalCapacity = 200),
            )
        // zone 11L 재고 없음(null)
        every { stockRedisGateway.getAll(listOf(10L, 11L)) } returns mapOf(10L to 57)
        every { reservationRepository.countOccupiedByZone(eq(1L), any()) } returns mapOf(11L to 150)

        val result = eventStockReader.readStockInfoList(eventId = 1L, zones = zones)

        assertThat(result.find { it.totalCapacity == 100 }!!.currentStock).isEqualTo(57)
        assertThat(result.find { it.totalCapacity == 200 }!!.currentStock).isEqualTo(50) // 200 - 150
    }

    @Test
    fun `DB fallback 재고가 음수이면 0으로 보정한다`() {
        val zones = listOf(detailZoneRecord(id = 10L, totalCapacity = 100))
        every { stockRedisGateway.getAll(listOf(10L)) } returns emptyMap()
        every { reservationRepository.countOccupiedByZone(eq(1L), any()) } returns mapOf(10L to 120)

        val result = eventStockReader.readStockInfoList(eventId = 1L, zones = zones)

        assertThat(result[0].currentStock).isZero()
    }

    @Test
    fun `Redis 장애 시 DB fallback으로 모든 zone 재고를 산정한다`() {
        val zones =
            listOf(
                detailZoneRecord(id = 10L, totalCapacity = 100),
                detailZoneRecord(id = 11L, totalCapacity = 200),
            )
        every { stockRedisGateway.getAll(any()) } throws RedisConnectionFailureException("redis down")
        every { reservationRepository.countOccupiedByZone(eq(1L), any()) } returns mapOf(10L to 100, 11L to 0)

        val result = eventStockReader.readStockInfoList(eventId = 1L, zones = zones)

        assertThat(result.find { it.totalCapacity == 100 }!!.currentStock).isZero()
        assertThat(result.find { it.totalCapacity == 200 }!!.currentStock).isEqualTo(200)
    }

    @Test
    fun `Redis 재고가 음수이면 매진으로 판단한다`() {
        val zones = listOf(detailZoneRecord(id = 10L, totalCapacity = 100))
        // 드리프트 등으로 Redis 재고가 음수인 경우
        every { stockRedisGateway.getAll(listOf(10L)) } returns mapOf(10L to -1)

        val result = eventStockReader.readStockInfoList(eventId = 1L, zones = zones)

        // currentStock은 coerceAtLeast(0) 보정 후 0 반환
        assertThat(result[0].currentStock).isEqualTo(-1)
    }

    // ────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────

    private fun zoneListRecord(
        eventId: Long,
        zoneId: Long,
        unitPrice: Int = 100_000,
        totalCapacity: Int = 100,
    ) = EventListZoneRecord(
        eventId = eventId,
        zoneId = zoneId,
        unitPrice = unitPrice,
        totalCapacity = totalCapacity,
    )

    private fun detailZoneRecord(
        id: Long,
        totalCapacity: Int,
    ) = EventDetailZoneRecord(
        id = id,
        publicId = UUID.randomUUID().toString(),
        name = "구역 $id",
        unitPrice = 100_000,
        totalCapacity = totalCapacity,
    )
}
