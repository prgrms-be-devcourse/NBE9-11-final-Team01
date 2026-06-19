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
class EventBulkCreateRedisFailureIntegrationTest(
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
    fun `Redis 초기화 실패 시 이벤트와 구역 저장을 롤백한다`() {
        redis.stop()

        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = createRequest()
            }.andExpect {
                status { isInternalServerError() }
                jsonPath("$.code") { value(ErrorCode.EVENT_CREATION_FAILED.code) }
            }

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

    private fun createRequest(): String =
        """
        {
          "name": "2027 SnapTix Concert",
          "description": "Redis failure rollback integration test",
          "location": "KSPO DOME",
          "startTime": "2027-12-25T19:00:00+09:00",
          "endTime": "2027-12-25T22:00:00+09:00",
          "initialStatus": "PENDING",
          "posterUrl": "https://cdn.snaptix.kr/events/test.jpg",
          "zones": [
            {
              "name": "VIP",
              "unitPrice": 150000,
              "totalCapacity": 100
            }
          ]
        }
        """.trimIndent()

    private data class EventsAndZonesCount(
        val events: Long,
        val zones: Long,
    )
}
