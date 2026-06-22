package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventStatusUpdateIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val redisTemplate: StringRedisTemplate,
) {
    companion object {
        @Container
        @JvmStatic
        val mysql =
            MySQLContainer("mysql:9.7").apply {
                withDatabaseName("snaptix")
                withUsername("snaptix")
                withPassword("snaptix1234")
            }

        @Container
        @JvmStatic
        val redis =
            GenericContainer("redis:8.8.0").apply {
                withExposedPorts(6379)
            }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-event-status-256-bit" }
        }
    }

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

    private fun insertEvent(status: EventStatus): String {
        val publicId = UUID.randomUUID().toString()
        val now = Instant.parse("2027-12-25T10:00:00Z")

        transaction {
            EventsTable.insert {
                it[EventsTable.publicId] = publicId
                it[EventsTable.name] = "2027 SnapTix Concert"
                it[EventsTable.location] = "KSPO DOME"
                it[EventsTable.startTime] = now
                it[EventsTable.endTime] = now.plusSeconds(10_800)
                it[EventsTable.status] = status.name
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

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).takeIf { it.isNotEmpty() }?.let(redisTemplate::delete)
    }

    private fun findEventStatus(eventId: String): String =
        transaction {
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
