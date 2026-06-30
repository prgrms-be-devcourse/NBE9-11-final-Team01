package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
class EventStatusUpdateIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val eventRepository: EventRepository,
    @Autowired private val orderStreamProperties: OrderStreamProperties,
) : IntegrationTestSupport() {
    @BeforeEach
    fun setUp() {
        transaction {
            ZonesTable.deleteAll()
            EventsTable.deleteAll()
        }
        deleteRedisKeys("ZONE:*:stock")
        deleteRedisKeys("ZONE:*:claimed")
        deleteRedisKeys("queue:order:*")
    }

    @Test
    fun `ADMIN은 이벤트 상태를 변경할 수 있다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(eventId) }
                jsonPath("$.status") { value("ON_SALE") }
                jsonPath("$.message") { value("이벤트 상태가 변경되었습니다.") }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.ON_SALE.name)
    }

    @Test
    fun `ON_SALE 이벤트는 SOLD_OUT으로 변경할 수 있다`() {
        val eventId = insertEvent(status = EventStatus.ON_SALE)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"SOLD_OUT"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(eventId) }
                jsonPath("$.status") { value("SOLD_OUT") }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.SOLD_OUT.name)
    }

    @Test
    fun `SOLD_OUT 이벤트는 CLOSED로 변경할 수 있다`() {
        val eventId = insertEvent(status = EventStatus.SOLD_OUT)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"CLOSED"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(eventId) }
                jsonPath("$.status") { value("CLOSED") }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.CLOSED.name)
    }

    @Test
    fun `인증 없이 이벤트 상태를 변경할 수 없다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(ErrorCode.UNAUTHORIZED.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.PENDING.name)
    }

    @Test
    fun `USER 권한은 이벤트 상태를 변경할 수 없다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("user").roles("USER"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ACCESS_DENIED.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.PENDING.name)
    }

    @Test
    fun `존재하지 않는 이벤트 상태 변경 요청은 실패한다`() {
        val eventId = UUID.randomUUID().toString()

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.EVENT_NOT_FOUND.code) }
            }
    }

    @Test
    fun `허용되지 않는 이벤트 상태 전이는 실패한다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"CLOSED"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.PENDING.name)
    }

    @Test
    fun `동일한 이벤트 상태로 변경할 수 없다`() {
        val eventId = insertEvent(status = EventStatus.ON_SALE)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.ON_SALE.name)
    }

    @Test
    fun `이벤트 상태를 역방향으로 변경할 수 없다`() {
        val eventId = insertEvent(status = EventStatus.SOLD_OUT)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.SOLD_OUT.name)
    }

    @Test
    fun `허용되지 않는 상태 전이 조합은 이벤트 상태를 변경하지 않는다`() {
        listOf(
            EventStatus.PENDING to EventStatus.SOLD_OUT,
            EventStatus.CLOSED to EventStatus.ON_SALE,
            EventStatus.CLOSED to EventStatus.SOLD_OUT,
        ).forEach { (currentStatus, nextStatus) ->
            assertStatusTransitionFails(currentStatus, nextStatus)
        }
    }

    @Test
    fun `변경할 이벤트 상태가 없으면 실패한다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.PENDING.name)
    }

    @Test
    fun `존재하지 않는 이벤트 상태값이면 실패한다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"OPEN"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.PENDING.name)
    }

    @Test
    fun `저장된 이벤트 상태값이 올바르지 않으면 상태 변경에 실패한다`() {
        val eventId = insertEvent(status = "UNKNOWN")

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"ON_SALE"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo("UNKNOWN")
    }

    @Test
    fun `조건부 상태 변경은 현재 상태가 일치하지 않으면 실패한다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)

        val updatedRows =
            transaction {
                eventRepository.updateStatusByPublicId(
                    publicId = eventId,
                    currentStatus = EventStatus.ON_SALE,
                    status = EventStatus.CLOSED,
                )
            }

        assertThat(updatedRows).isZero()
        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.PENDING.name)
    }

    @Test
    fun `동시에 같은 상태 변경을 요청해도 하나의 조건부 UPDATE만 성공한다`() {
        val eventId = insertEvent(status = EventStatus.PENDING)
        val workerCount = 8
        val executor = Executors.newFixedThreadPool(workerCount)
        val readyLatch = CountDownLatch(workerCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workerCount)
        val updatedRows = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        try {
            repeat(workerCount) {
                executor.submit {
                    readyLatch.countDown()
                    try {
                        startLatch.await()
                        val result =
                            transaction {
                                eventRepository.updateStatusByPublicId(
                                    publicId = eventId,
                                    currentStatus = EventStatus.PENDING,
                                    status = EventStatus.ON_SALE,
                                )
                            }
                        updatedRows.add(result)
                    } catch (exception: Throwable) {
                        failures.add(exception)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue()
            startLatch.countDown()
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdownNow()
        }

        assertThat(failures).isEmpty()
        assertThat(updatedRows).hasSize(workerCount)
        assertThat(updatedRows.count { it == 1 }).isEqualTo(1)
        assertThat(updatedRows.count { it == 0 }).isEqualTo(workerCount - 1)
        assertThat(findEventStatus(eventId)).isEqualTo(EventStatus.ON_SALE.name)
    }

    @Test
    fun `이벤트가 CLOSED로 변경되면 Redis 운영 키를 정리한다`() {
        val event = insertEventWithZones(status = EventStatus.ON_SALE)
        val redisKeys = seedRedisKeys(event, includeOrderStream = false)

        redisKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isTrue()
        }

        mockMvc
            .patch("/api/v1/admin/events/${event.publicId}/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"CLOSED"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(event.publicId) }
                jsonPath("$.status") { value("CLOSED") }
            }

        assertThat(findEventStatus(event.publicId)).isEqualTo(EventStatus.CLOSED.name)
        redisKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isFalse()
        }
    }

    @Test
    fun `이벤트가 CLOSED로 변경되어도 미처리 주문 Stream은 삭제하지 않는다`() {
        val event = insertEventWithZones(status = EventStatus.ON_SALE)
        val redisKeys = seedRedisKeys(event, includeOrderStream = true)
        val orderStreamKey = "queue:order:${event.publicId}"

        redisKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isTrue()
        }

        mockMvc
            .patch("/api/v1/admin/events/${event.publicId}/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"CLOSED"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(event.publicId) }
                jsonPath("$.status") { value("CLOSED") }
            }

        assertThat(findEventStatus(event.publicId)).isEqualTo(EventStatus.CLOSED.name)
        assertThat(redisTemplate.hasKey(orderStreamKey)).isTrue()
        redisKeys
            .filterNot { it == orderStreamKey }
            .forEach { key ->
                assertThat(redisTemplate.hasKey(key)).isFalse()
            }
    }

    @Test
    fun `이벤트가 CLOSED로 변경되어도 PEL에 남은 주문 Stream은 삭제하지 않는다`() {
        val event = insertEventWithZones(status = EventStatus.ON_SALE)
        val redisKeys = seedRedisKeys(event, includeOrderStream = false)
        val orderStreamKey = seedPendingOrderStream(event)
        val allKeys = redisKeys + orderStreamKey

        allKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isTrue()
        }

        mockMvc
            .patch("/api/v1/admin/events/${event.publicId}/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"CLOSED"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(event.publicId) }
                jsonPath("$.status") { value("CLOSED") }
            }

        assertThat(findEventStatus(event.publicId)).isEqualTo(EventStatus.CLOSED.name)
        assertThat(redisTemplate.hasKey(orderStreamKey)).isTrue()
        redisKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isFalse()
        }
    }

    @Test
    fun `이벤트가 CLOSED로 변경되면 ACK 완료된 주문 Stream은 삭제한다`() {
        val event = insertEventWithZones(status = EventStatus.ON_SALE)
        val redisKeys = seedRedisKeys(event, includeOrderStream = false)
        val orderStreamKey = seedAcknowledgedOrderStream(event)
        val allKeys = redisKeys + orderStreamKey

        allKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isTrue()
        }

        mockMvc
            .patch("/api/v1/admin/events/${event.publicId}/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"CLOSED"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(event.publicId) }
                jsonPath("$.status") { value("CLOSED") }
            }

        assertThat(findEventStatus(event.publicId)).isEqualTo(EventStatus.CLOSED.name)
        allKeys.forEach { key ->
            assertThat(redisTemplate.hasKey(key)).isFalse()
        }
    }

    private fun assertStatusTransitionFails(
        currentStatus: EventStatus,
        nextStatus: EventStatus,
    ) {
        val eventId = insertEvent(status = currentStatus)

        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"${nextStatus.name}"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertThat(findEventStatus(eventId)).isEqualTo(currentStatus.name)
    }

    private fun insertEvent(status: EventStatus): String = insertEvent(status = status.name)

    private fun insertEvent(status: String): String {
        val publicId = UUID.randomUUID().toString()
        val now = Instant.parse("2027-12-25T10:00:00Z")

        transaction {
            EventsTable.insert {
                it[EventsTable.publicId] = publicId
                it[EventsTable.name] = "2027 SnapTix Concert"
                it[EventsTable.location] = "KSPO DOME"
                it[EventsTable.startTime] = now
                it[EventsTable.endTime] = now.plusSeconds(10_800)
                it[EventsTable.status] = status
            }
        }

        return publicId
    }

    private fun insertEventWithZones(status: EventStatus): CreatedEvent {
        val publicId = UUID.randomUUID().toString()
        val now = Instant.parse("2027-12-25T10:00:00Z")

        return transaction {
            val eventId =
                EventsTable.insert {
                    it[EventsTable.publicId] = publicId
                    it[EventsTable.name] = "2027 SnapTix Concert"
                    it[EventsTable.location] = "KSPO DOME"
                    it[EventsTable.startTime] = now
                    it[EventsTable.endTime] = now.plusSeconds(10_800)
                    it[EventsTable.status] = status.name
                }[EventsTable.id]
            val zoneIds =
                listOf("VIP", "A").map { zoneName ->
                    ZonesTable.insert {
                        it[ZonesTable.publicId] = UUID.randomUUID().toString()
                        it[ZonesTable.eventId] = eventId
                        it[ZonesTable.name] = zoneName
                        it[ZonesTable.unitPrice] = 100_000
                        it[ZonesTable.totalCapacity] = 100
                    }[ZonesTable.id]
                }

            CreatedEvent(publicId = publicId, zoneIds = zoneIds)
        }
    }

    private fun seedRedisKeys(
        event: CreatedEvent,
        includeOrderStream: Boolean,
    ): List<String> {
        val keys =
            buildList {
                add("event:info:${event.publicId}")
                if (includeOrderStream) {
                    add("queue:order:${event.publicId}")
                }
                event.zoneIds.forEach { zoneId ->
                    add("ZONE:$zoneId:stock")
                    add("ZONE:$zoneId:claimed")
                }
            }

        val eventInfo = redisTemplate.opsForHash<String, String>()
        eventInfo.put("event:info:${event.publicId}", "status", EventStatus.ON_SALE.name)
        if (includeOrderStream) {
            val orderStream = redisTemplate.opsForStream<String, String>()
            orderStream.add("queue:order:${event.publicId}", mapOf("orderId" to "test-order"))
        }
        event.zoneIds.forEach { zoneId ->
            redisTemplate.opsForValue().set("ZONE:$zoneId:stock", "1")
            redisTemplate.opsForSet().add("ZONE:$zoneId:claimed", "user-1")
        }

        return keys
    }

    private fun seedPendingOrderStream(event: CreatedEvent): String {
        val orderStreamKey = "queue:order:${event.publicId}"
        val streamOperations = redisTemplate.opsForStream<String, String>()

        streamOperations.add(orderStreamKey, mapOf("orderId" to "test-order"))
        streamOperations.createGroup(orderStreamKey, ReadOffset.from("0-0"), orderStreamProperties.consumerGroup)
        streamOperations.read(
            Consumer.from(orderStreamProperties.consumerGroup, "consumer-1"),
            StreamOffset.create(orderStreamKey, ReadOffset.lastConsumed()),
        )

        return orderStreamKey
    }

    private fun seedAcknowledgedOrderStream(event: CreatedEvent): String {
        val orderStreamKey = "queue:order:${event.publicId}"
        val streamOperations = redisTemplate.opsForStream<String, String>()
        val recordId = streamOperations.add(orderStreamKey, mapOf("orderId" to "test-order"))

        streamOperations.createGroup(orderStreamKey, ReadOffset.from("0-0"), orderStreamProperties.consumerGroup)
        streamOperations.read(
            Consumer.from(orderStreamProperties.consumerGroup, "consumer-1"),
            StreamOffset.create(orderStreamKey, ReadOffset.lastConsumed()),
        )
        streamOperations.acknowledge(orderStreamKey, orderStreamProperties.consumerGroup, recordId)

        return orderStreamKey
    }

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).takeIf { it.isNotEmpty() }?.let(redisTemplate::delete)
    }

    private fun findEventStatus(eventId: String): String = transaction {
        EventsTable
            .selectAll()
            .where { EventsTable.publicId eq eventId }
            .single()[EventsTable.status]
    }

    private data class CreatedEvent(
        val publicId: String,
        val zoneIds: List<Long>,
    )
}
