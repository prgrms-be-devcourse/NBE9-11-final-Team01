package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventBulkCreateIntegrationTest(
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
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-event-flow-256-bit" }
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
    fun `관리자는 이벤트와 구역을 등록할 수 있다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = createRequest()
            }.andExpect {
                status { isCreated() }
                jsonPath("$.eventId") { exists() }
                jsonPath("$.eventName") { value("2027 SnapTix Concert") }
                jsonPath("$.status") { value("PENDING") }
                jsonPath("$.registeredZones.length()") { value(2) }
                jsonPath("$.registeredZones[0].zoneId") { exists() }
                jsonPath("$.message") { value("이벤트 및 2개 구역 등록이 완료되었습니다.") }
            }

        val eventCount =
            transaction {
                EventsTable.selectAll().count()
            }
        assertThat(eventCount).isEqualTo(1)

        val zones =
            transaction {
                ZonesTable
                    .selectAll()
                    .map { it[ZonesTable.publicId] }
            }
        assertThat(zones).hasSize(2)
        assertThat(zones).allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `USER 권한은 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("user").roles("USER"))
                contentType = MediaType.APPLICATION_JSON
                content = createRequest()
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ACCESS_DENIED.code) }
            }

        assertEventAndZoneTablesAreEmpty()
    }

    @Test
    fun `초기 상태가 SOLD_OUT이면 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = createRequest(initialStatus = "SOLD_OUT")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertEventAndZoneTablesAreEmpty()
    }

    @Test
    fun `종료 시각이 시작 시각보다 빠르면 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    createRequest(
                        startTime = "2027-12-25T22:00:00+09:00",
                        endTime = "2027-12-25T19:00:00+09:00",
                    )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST_PARAMETER.code) }
            }

        assertEventAndZoneTablesAreEmpty()
    }

    @Test
    fun `구역 단가와 수용 인원이 허용 범위를 벗어나면 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    createRequest()
                        .replace("\"unitPrice\": 150000", "\"unitPrice\": 99")
                        .replace("\"totalCapacity\": 100", "\"totalCapacity\": 100001")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
            }

        assertEventAndZoneTablesAreEmpty()
    }

    private fun createRequest(
        initialStatus: String = "PENDING",
        startTime: String = "2027-12-25T19:00:00+09:00",
        endTime: String = "2027-12-25T22:00:00+09:00",
    ): String =
        """
        {
          "name": "2027 SnapTix Concert",
          "description": "SnapTix event bulk create integration test",
          "location": "KSPO DOME",
          "startTime": "$startTime",
          "endTime": "$endTime",
          "initialStatus": "$initialStatus",
          "posterUrl": "https://cdn.snaptix.kr/events/test.jpg",
          "zones": [
            {
              "name": "VIP",
              "unitPrice": 150000,
              "totalCapacity": 100
            },
            {
              "name": "A",
              "unitPrice": 99000,
              "totalCapacity": 200
            }
          ]
        }
        """.trimIndent()

    private fun assertEventAndZoneTablesAreEmpty() {
        val counts =
            transaction {
                EventsAndZonesCount(
                    events = EventsTable.selectAll().count(),
                    zones = ZonesTable.selectAll().count(),
                )
            }

        assertThat(counts.events).isZero()
        assertThat(counts.zones).isZero()
    }

    private data class EventsAndZonesCount(
        val events: Long,
        val zones: Long,
    )
}
