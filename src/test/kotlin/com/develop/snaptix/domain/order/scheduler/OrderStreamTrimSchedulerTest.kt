package com.develop.snaptix.domain.order.scheduler

import com.develop.snaptix.domain.event.repository.EventDetail
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.StreamTrimResult
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OrderStreamTrimSchedulerTest {
    private val eventRepository = mockk<EventRepository>()
    private val orderStreamGateway = mockk<OrderStreamGateway>()
    private lateinit var scheduler: OrderStreamTrimScheduler

    @BeforeEach
    fun setUp() {
        scheduler =
            OrderStreamTrimScheduler(
                eventRepository = eventRepository,
                orderStreamGateway = orderStreamGateway,
                enabled = true,
            )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `활성 이벤트마다 ACK 완료 Stream trim을 수행한다`() {
        val eventIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { eventRepository.findActiveEvents() } returns eventIds.mapIndexed(::activeEvent)
        eventIds.forEach {
            every { orderStreamGateway.trimAcknowledged(it, GROUP) } returns
                StreamTrimResult(
                    trimmedCount = 1L,
                    minId = "1-1",
                )
        }

        scheduler.trimAcknowledged()

        eventIds.forEach {
            verify(exactly = 1) { orderStreamGateway.trimAcknowledged(it, GROUP) }
        }
    }

    @Test
    fun `활성 이벤트가 없으면 trim을 수행하지 않는다`() {
        every { eventRepository.findActiveEvents() } returns emptyList()

        scheduler.trimAcknowledged()

        verify(exactly = 0) { orderStreamGateway.trimAcknowledged(any(), any()) }
    }

    @Test
    fun `특정 이벤트 trim이 실패해도 다음 이벤트 trim을 계속 수행한다`() {
        val failedEventId = UUID.randomUUID()
        val nextEventId = UUID.randomUUID()
        every { eventRepository.findActiveEvents() } returns
            listOf(activeEvent(1, failedEventId), activeEvent(2, nextEventId))
        every { orderStreamGateway.trimAcknowledged(failedEventId, GROUP) } throws RuntimeException("Redis down")
        every { orderStreamGateway.trimAcknowledged(nextEventId, GROUP) } returns
            StreamTrimResult(
                trimmedCount = 0L,
                minId = null,
            )

        scheduler.trimAcknowledged()

        verify(exactly = 1) { orderStreamGateway.trimAcknowledged(failedEventId, GROUP) }
        verify(exactly = 1) { orderStreamGateway.trimAcknowledged(nextEventId, GROUP) }
    }

    @Test
    fun `이벤트 publicId가 UUID 형식이 아니면 해당 이벤트만 건너뛰고 다음 이벤트 trim을 계속 수행한다`() {
        val nextEventId = UUID.randomUUID()
        every { eventRepository.findActiveEvents() } returns
            listOf(activeEvent(1, "not-a-uuid"), activeEvent(2, nextEventId.toString()))
        every { orderStreamGateway.trimAcknowledged(nextEventId, GROUP) } returns
            StreamTrimResult(
                trimmedCount = 0L,
                minId = null,
            )

        scheduler.trimAcknowledged()

        verify(exactly = 1) { orderStreamGateway.trimAcknowledged(nextEventId, GROUP) }
    }

    @Test
    fun `활성 이벤트 조회가 실패하면 trim을 수행하지 않는다`() {
        every { eventRepository.findActiveEvents() } throws RuntimeException("DB down")

        scheduler.trimAcknowledged()

        verify(exactly = 0) { orderStreamGateway.trimAcknowledged(any(), any()) }
    }

    @Test
    fun `스케줄러가 비활성화되면 이벤트 조회와 trim을 수행하지 않는다`() {
        val disabledScheduler =
            OrderStreamTrimScheduler(
                eventRepository = eventRepository,
                orderStreamGateway = orderStreamGateway,
                enabled = false,
            )

        disabledScheduler.trimAcknowledged()

        verify(exactly = 0) { eventRepository.findActiveEvents() }
        verify(exactly = 0) { orderStreamGateway.trimAcknowledged(any(), any()) }
    }

    private fun activeEvent(
        index: Int,
        publicId: UUID,
    ): EventDetail = activeEvent(index, publicId.toString())

    private fun activeEvent(
        index: Int,
        publicId: String,
    ): EventDetail = EventDetail(
        id = index.toLong(),
        publicId = publicId,
        name = "event-$index",
        description = null,
        location = "KSPO DOME",
        startTime = Instant.parse("2027-12-25T10:00:00Z"),
        endTime = Instant.parse("2027-12-25T13:00:00Z"),
        posterUrl = null,
        status = "ON_SALE",
    )

    companion object {
        private const val GROUP = "order-workers"
    }
}
