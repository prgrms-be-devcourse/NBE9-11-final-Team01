package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import org.hamcrest.Matchers.not
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.core.StringRedisTemplate
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
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val eventCacheRedisGateway: EventCacheRedisGateway,
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
            ReservationsTable.deleteAll()
            ZonesTable.deleteAll()
            EventsTable.deleteAll()
            UsersTable.deleteAll()
        }
        deleteRedisKeys("ZONE:*:stock")
        deleteRedisKeys("event:info:*")
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

    @Test
    fun `Redis 재고 키가 있으면 currentStock에 반영한다`() {
        val event = insertEventWithZones()
        redisTemplate.opsForValue().set("ZONE:${event.zones[0].id}:stock", "57")
        redisTemplate.opsForValue().set("ZONE:${event.zones[1].id}:stock", "0")

        mockMvc
            .get("/api/v1/events/${event.publicId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.zones[0].currentStock") { value(57) }
                jsonPath("$.zones[1].currentStock") { value(0) }
            }
    }

    @Test
    fun `Redis 재고 키가 없으면 MySQL 점유 수 기준으로 currentStock을 계산한다`() {
        val event = insertEventWithZones()
        val userId = insertUser()
        insertReservation(
            userId = userId,
            eventId = event.id,
            zoneId = event.zones[0].id,
            status = ReservationStatus.CONFIRMED,
        )
        insertReservation(
            userId = userId,
            eventId = event.id,
            zoneId = event.zones[1].id,
            status = ReservationStatus.CONFIRMED,
        )

        mockMvc
            .get("/api/v1/events/${event.publicId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.zones[0].currentStock") { value(99) }
                jsonPath("$.zones[1].currentStock") { value(199) }
            }
    }

    @Test
    fun `이벤트 메타데이터 캐시가 있으면 캐시 값을 응답에 사용한다`() {
        val event = insertEventWithZones()
        eventCacheRedisGateway.put(
            UUID.fromString(event.publicId),
            EventInfo(
                eventId = event.publicId,
                name = "Cached Concert",
                description = "캐시된 이벤트 설명",
                location = "캐시 공연장",
                startTime = "2027-12-26T10:00:00Z",
                endTime = "2027-12-26T13:00:00Z",
                status = "SOLD_OUT",
                posterUrl = "https://cdn.snaptix.kr/events/cached.jpg",
            ),
        )

        mockMvc
            .get("/api/v1/events/${event.publicId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Cached Concert") }
                jsonPath("$.description") { value("캐시된 이벤트 설명") }
                jsonPath("$.location") { value("캐시 공연장") }
                jsonPath("$.startTime") { value("2027-12-26T19:00:00+09:00") }
                jsonPath("$.endTime") { value("2027-12-26T22:00:00+09:00") }
                jsonPath("$.status") { value("SOLD_OUT") }
                jsonPath("$.posterUrl") { value("https://cdn.snaptix.kr/events/cached.jpg") }
                jsonPath("$.zones.length()") { value(2) }
            }
    }

    @Test
    fun `매진 상태 이벤트 상세는 조회할 수 있다`() {
        val event = insertEventWithZones(status = EventStatus.SOLD_OUT)

        mockMvc
            .get("/api/v1/events/${event.publicId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(event.publicId) }
                jsonPath("$.status") { value("SOLD_OUT") }
            }
    }

    @Test
    fun `공개되지 않은 이벤트 상세 조회는 404를 응답한다`() {
        val pending = insertEventWithZones(status = EventStatus.PENDING)
        val closed = insertEventWithZones(status = EventStatus.CLOSED)

        assertEventNotFound(pending.publicId)
        assertEventNotFound(closed.publicId)
    }

    private fun assertEventNotFound(eventId: String) {
        mockMvc
            .get("/api/v1/events/$eventId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.EVENT_NOT_FOUND.code) }
            }
    }

    private fun insertEventWithZones(status: EventStatus = EventStatus.ON_SALE): CreatedEvent = transaction {
        val eventPublicId = UUID.randomUUID().toString()
        val eventId =
            EventsTable.insert {
                it[EventsTable.publicId] = eventPublicId
                it[EventsTable.name] = "2027 SnapTix Concert"
                it[EventsTable.description] = "인기 아티스트 콘서트입니다."
                it[EventsTable.location] = "올림픽공원 체조경기장"
                it[EventsTable.startTime] = Instant.parse("2027-12-25T10:00:00Z")
                it[EventsTable.endTime] = Instant.parse("2027-12-25T13:00:00Z")
                it[EventsTable.status] = status.name
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

    private fun insertUser(): Long = transaction {
        UsersTable.insert {
            it[UsersTable.email] = "${UUID.randomUUID()}@example.com"
            it[UsersTable.password] = "encoded-password"
            it[UsersTable.role] = "USER"
        }[UsersTable.id]
    }

    private fun insertReservation(
        userId: Long,
        eventId: Long,
        zoneId: Long,
        status: ReservationStatus,
    ) {
        transaction {
            ReservationsTable.insert {
                it[ReservationsTable.orderId] = UUID.randomUUID().toString()
                it[ReservationsTable.userId] = userId
                it[ReservationsTable.eventId] = eventId
                it[ReservationsTable.zoneId] = zoneId
                it[ReservationsTable.amount] = 100_000
                it[ReservationsTable.status] = status.name
            }
        }
    }

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).takeIf { it.isNotEmpty() }?.let(redisTemplate::delete)
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
