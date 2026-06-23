package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import org.hamcrest.Matchers.not
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventDetailIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
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
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-event-detail-256-bit" }
        }
    }

    @BeforeEach
    fun setUp() {
        transaction {
            ZonesTable.deleteAll()
            EventsTable.deleteAll()
        }
    }

    @Test
    fun `이벤트 상세는 인증 없이 publicId 기반 이벤트와 구역 정보를 조회한다`() {
        val event = insertEventWithZones()

        mockMvc
            .get("/api/v1/events/${event.publicId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(event.publicId) }
                jsonPath("$.eventId") { value(not(event.id.toString())) }
                jsonPath("$.name") { value("2027 SnapTix Concert") }
                jsonPath("$.description") { value("인기 아티스트 콘서트입니다.") }
                jsonPath("$.location") { value("올림픽공원 체조경기장") }
                jsonPath("$.posterUrl") { value("https://cdn.snaptix.kr/events/detail.jpg") }
                jsonPath("$.startTime") { value("2027-12-25T19:00:00+09:00") }
                jsonPath("$.endTime") { value("2027-12-25T22:00:00+09:00") }
                jsonPath("$.status") { value("ON_SALE") }
                jsonPath("$.zones.length()") { value(2) }
                jsonPath("$.zones[0].zoneId") { value(event.zones[0].publicId) }
                jsonPath("$.zones[0].zoneId") { value(not(event.zones[0].id.toString())) }
                jsonPath("$.zones[0].name") { value("VIP") }
                jsonPath("$.zones[0].unitPrice") { value(150_000) }
                jsonPath("$.zones[0].totalCapacity") { value(100) }
                jsonPath("$.zones[0].currentStock") { value(100) }
                jsonPath("$.zones[1].zoneId") { value(event.zones[1].publicId) }
                jsonPath("$.zones[1].zoneId") { value(not(event.zones[1].id.toString())) }
                jsonPath("$.zones[1].name") { value("A") }
                jsonPath("$.zones[1].unitPrice") { value(90_000) }
                jsonPath("$.zones[1].totalCapacity") { value(200) }
                jsonPath("$.zones[1].currentStock") { value(200) }
            }
    }

    @Test
    fun `존재하지 않는 이벤트 상세 조회는 404를 응답한다`() {
        mockMvc
            .get("/api/v1/events/${UUID.randomUUID()}")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.EVENT_NOT_FOUND.code) }
            }
    }

    private fun insertEventWithZones(): CreatedEvent = transaction {
        val eventPublicId = UUID.randomUUID().toString()
        val eventId =
            EventsTable.insert {
                it[EventsTable.publicId] = eventPublicId
                it[EventsTable.name] = "2027 SnapTix Concert"
                it[EventsTable.description] = "인기 아티스트 콘서트입니다."
                it[EventsTable.location] = "올림픽공원 체조경기장"
                it[EventsTable.startTime] = Instant.parse("2027-12-25T10:00:00Z")
                it[EventsTable.endTime] = Instant.parse("2027-12-25T13:00:00Z")
                it[EventsTable.status] = EventStatus.ON_SALE.name
                it[EventsTable.posterUrl] = "https://cdn.snaptix.kr/events/detail.jpg"
            }[EventsTable.id]
        val zones =
            listOf(
                insertZone(eventId = eventId, name = "VIP", unitPrice = 150_000, totalCapacity = 100),
                insertZone(eventId = eventId, name = "A", unitPrice = 90_000, totalCapacity = 200),
            )

        CreatedEvent(
            id = eventId,
            publicId = eventPublicId,
            zones = zones,
        )
    }

    private fun insertZone(
        eventId: Long,
        name: String,
        unitPrice: Int,
        totalCapacity: Int,
    ): CreatedZone {
        val zonePublicId = UUID.randomUUID().toString()
        val zoneId =
            ZonesTable.insert {
                it[ZonesTable.publicId] = zonePublicId
                it[ZonesTable.eventId] = eventId
                it[ZonesTable.name] = name
                it[ZonesTable.unitPrice] = unitPrice
                it[ZonesTable.totalCapacity] = totalCapacity
            }[ZonesTable.id]

        return CreatedZone(
            id = zoneId,
            publicId = zonePublicId,
        )
    }

    private data class CreatedEvent(
        val id: Long,
        val publicId: String,
        val zones: List<CreatedZone>,
    )

    private data class CreatedZone(
        val id: Long,
        val publicId: String,
    )
}
