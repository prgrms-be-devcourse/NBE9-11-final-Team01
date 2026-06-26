package com.develop.snaptix.global.resilience

import com.develop.snaptix.domain.event.repository.EventDetail
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * RebuildRedisWriter 단위 테스트 (MockK).
 *
 * 게이트웨이 위임이라 순수 단위 테스트 가능:
 *  - rebuildZone : `List<String>` → `UUID` 변환 후 StockRedisGateway.rebuild 위임
 *  - writeEventInfo : EventDetail → EventInfo 매핑(특히 totalCapacity 채움, null 필드 orEmpty)
 */
class RebuildRedisWriterTest {
    private val stockGateway = mockk<StockRedisGateway>(relaxUnitFun = true)
    private val eventCacheGateway = mockk<EventCacheRedisGateway>(relaxUnitFun = true)
    private val writer = RebuildRedisWriter(stockGateway, eventCacheGateway)

    @Test
    fun `should_orderId를_UUID로_변환해_rebuild위임_when_rebuildZone하면`() {
        val o1 = UUID.randomUUID()
        val o2 = UUID.randomUUID()

        writer.rebuildZone(zoneId = 10L, stock = 60, claimedOrderIds = listOf(o1.toString(), o2.toString()))

        verify(exactly = 1) { stockGateway.rebuild(10L, 60, listOf(o1, o2)) }
    }

    @Test
    fun `should_EventInfo로_매핑하고_totalCapacity_채워_put_when_writeEventInfo하면`() {
        val eventPublicId = UUID.randomUUID()
        val event = mockk<EventDetail>()
        every { event.publicId } returns eventPublicId.toString()
        every { event.name } returns "SnapTix Concert"
        every { event.description } returns null // null → orEmpty()
        every { event.location } returns "KSPO DOME"
        every { event.startTime } returns Instant.parse("2027-12-25T10:00:00Z")
        every { event.endTime } returns Instant.parse("2027-12-25T13:00:00Z")
        every { event.status } returns "ON_SALE"
        every { event.posterUrl } returns null

        val captured = slot<EventInfo>()
        every { eventCacheGateway.put(eventPublicId, capture(captured)) } just Runs

        writer.writeEventInfo(event, totalCapacity = 100)

        verify(exactly = 1) { eventCacheGateway.put(eventPublicId, any()) }
        assertThat(captured.captured.eventId).isEqualTo(eventPublicId.toString())
        assertThat(captured.captured.totalCapacity).isEqualTo(100) // 누락 시 OrderIngest RECONCILE_FAILED → 반드시 채움
        assertThat(captured.captured.description).isEmpty() // null → orEmpty
        assertThat(captured.captured.posterUrl).isEmpty()
        assertThat(captured.captured.status).isEqualTo("ON_SALE")
    }
}
