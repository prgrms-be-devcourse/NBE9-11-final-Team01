package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
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
class EventListIntegrationTest(
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
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-event-list-256-bit" }
        }
    }

    @BeforeEach
    fun setUp() {
        transaction {
            ZonesTable.deleteAll()
            EventsTable.deleteAll()
        }
        deleteRedisKeys("ZONE:*:stock")
        deleteRedisKeys("rate_limit:*")
    }

    @Test
    fun `이벤트 목록은 인증 없이 ON_SALE 이벤트만 조회한다`() {
        val onSale =
            insertEventWithZones(
                name = "2027 SnapTix Seoul",
                status = EventStatus.ON_SALE,
                startTime = Instant.parse("2027-12-25T10:00:00Z"),
                location = "서울 KSPO DOME",
                zonePrices = listOf(150_000, 90_000),
                zoneStocks = listOf(0, 5),
            )
        insertEventWithZones(name = "Pending Event", status = EventStatus.PENDING)
        insertEventWithZones(name = "Sold Out Event", status = EventStatus.SOLD_OUT)
        insertEventWithZones(name = "Closed Event", status = EventStatus.CLOSED)

        mockMvc
            .get("/api/v1/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].eventId") { value(onSale.publicId) }
                jsonPath("$.content[0].name") { value("2027 SnapTix Seoul") }
                jsonPath("$.content[0].location") { value("서울 KSPO DOME") }
                jsonPath("$.content[0].startTime") { value("2027-12-25T19:00:00+09:00") }
                jsonPath("$.content[0].status") { value("ON_SALE") }
                jsonPath("$.content[0].minPrice") { value(90_000) }
                jsonPath("$.content[0].isSoldOut") { value(false) }
                jsonPath("$.pageable.pageNumber") { value(0) }
                jsonPath("$.pageable.pageSize") { value(20) }
                jsonPath("$.pageable.totalElements") { value(1) }
                jsonPath("$.pageable.totalPages") { value(1) }
            }
    }

    @Test
    fun `모든 구역 재고가 0이면 매진 상태로 응답한다`() {
        insertEventWithZones(
            name = "Sold Out On Sale Event",
            status = EventStatus.ON_SALE,
            zonePrices = listOf(170_000, 110_000),
            zoneStocks = listOf(0, 0),
        )

        mockMvc
            .get("/api/v1/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.content[0].minPrice") { value(110_000) }
                jsonPath("$.content[0].isSoldOut") { value(true) }
            }
    }

    @Test
    fun `페이징과 시작 시각 내림차순 정렬을 지원한다`() {
        insertEventWithZones(
            name = "Day 1",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-25T10:00:00Z"),
        )
        insertEventWithZones(
            name = "Day 2",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-26T10:00:00Z"),
        )
        insertEventWithZones(
            name = "Day 3",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-27T10:00:00Z"),
        )

        mockMvc
            .get("/api/v1/events") {
                param("page", "0")
                param("size", "2")
                param("sortBy", "startTime")
                param("sortDir", "desc")
            }.andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(2) }
                jsonPath("$.content[0].name") { value("Day 3") }
                jsonPath("$.content[1].name") { value("Day 2") }
                jsonPath("$.pageable.pageNumber") { value(0) }
                jsonPath("$.pageable.pageSize") { value(2) }
                jsonPath("$.pageable.totalElements") { value(3) }
                jsonPath("$.pageable.totalPages") { value(2) }
            }
    }

    @Test
    fun `장소명 부분 검색을 지원한다`() {
        insertEventWithZones(name = "Seoul Event", status = EventStatus.ON_SALE, location = "서울 KSPO DOME")
        insertEventWithZones(name = "Busan Event", status = EventStatus.ON_SALE, location = "부산 BEXCO")

        mockMvc
            .get("/api/v1/events") {
                param("location", "부산")
            }.andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].name") { value("Busan Event") }
                jsonPath("$.content[0].location") { value("부산 BEXCO") }
            }
    }

    @Test
    fun `날짜 필터는 KST 기준 종료일 당일까지 포함한다`() {
        insertEventWithZones(
            name = "Before Range",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-25T10:00:00Z"),
        )
        insertEventWithZones(
            name = "Start Date",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-26T10:00:00Z"),
        )
        insertEventWithZones(
            name = "End Date",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-28T10:00:00Z"),
        )
        insertEventWithZones(
            name = "After Range",
            status = EventStatus.ON_SALE,
            startTime = Instant.parse("2027-12-29T10:00:00Z"),
        )

        mockMvc
            .get("/api/v1/events") {
                param("startDate", "2027-12-26")
                param("endDate", "2027-12-28")
            }.andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(2) }
                jsonPath("$.content[0].name") { value("Start Date") }
                jsonPath("$.content[1].name") { value("End Date") }
            }
    }

    @Test
    fun `쿼리 파라미터 검증에 실패하면 400을 응답한다`() {
        assertValidationFailed("size", "51")
        assertValidationFailed("page", "1001")
        assertValidationFailed("page", "-1")
    }

    @Test
    fun `허용되지 않는 정렬 조건이면 400을 응답한다`() {
        mockMvc
            .get("/api/v1/events") {
                param("sortBy", "id")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        mockMvc
            .get("/api/v1/events") {
                param("sortDir", "latest")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }
    }

    @Test
    fun `조회 시작일이 종료일보다 이후이면 400을 응답한다`() {
        mockMvc
            .get("/api/v1/events") {
                param("startDate", "2027-12-31")
                param("endDate", "2027-12-01")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }
    }

    private fun assertValidationFailed(
        parameter: String,
        value: String,
    ) {
        mockMvc
            .get("/api/v1/events") {
                param(parameter, value)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
            }
    }

    private fun insertEventWithZones(
        name: String,
        status: EventStatus,
        location: String = "KSPO DOME",
        startTime: Instant = Instant.parse("2027-12-25T10:00:00Z"),
        zonePrices: List<Int> = listOf(100_000),
        zoneStocks: List<Int> = zonePrices.map { 1 },
    ): CreatedEvent {
        require(zonePrices.size == zoneStocks.size)

        return transaction {
            val eventPublicId = UUID.randomUUID().toString()
            val eventId =
                EventsTable.insert {
                    it[EventsTable.publicId] = eventPublicId
                    it[EventsTable.name] = name
                    it[EventsTable.location] = location
                    it[EventsTable.startTime] = startTime
                    it[EventsTable.endTime] = startTime.plusSeconds(10_800)
                    it[EventsTable.status] = status.name
                    it[EventsTable.posterUrl] = "https://cdn.snaptix.kr/events/test.jpg"
                }[EventsTable.id]
            val zoneIds =
                zonePrices.mapIndexed { index, unitPrice ->
                    ZonesTable.insert {
                        it[ZonesTable.publicId] = UUID.randomUUID().toString()
                        it[ZonesTable.eventId] = eventId
                        it[ZonesTable.name] = "Zone ${index + 1}"
                        it[ZonesTable.unitPrice] = unitPrice
                        it[ZonesTable.totalCapacity] = 100
                    }[ZonesTable.id]
                }

            zoneIds.zip(zoneStocks).forEach { (zoneId, stock) ->
                redisTemplate.opsForValue().set("ZONE:$zoneId:stock", stock.toString())
            }

            CreatedEvent(publicId = eventPublicId)
        }
    }

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).takeIf { it.isNotEmpty() }?.let(redisTemplate::delete)
    }

    private data class CreatedEvent(
        val publicId: String,
    )
}
