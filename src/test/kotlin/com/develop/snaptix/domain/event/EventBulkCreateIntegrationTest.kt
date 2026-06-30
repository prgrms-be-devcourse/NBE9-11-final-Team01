package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import com.develop.snaptix.support.IntegrationTestSupport
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
class EventBulkCreateIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) : IntegrationTestSupport() {
    @BeforeEach
    fun setUp() {
        transaction {
            ZonesTable.deleteAll()
            EventsTable.deleteAll()
        }
        deleteRedisKeys("ZONE:*:stock")
        deleteRedisKeys("event:info:*")
        deleteRedisKeys("queue:order:*")
    }

    @Test
    fun `관리자는 이벤트와 구역을 등록할 수 있다`() {
        val result =
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
                    jsonPath("$.registeredZones[0].name") { value("VIP") }
                    jsonPath("$.registeredZones[0].redisStockKey") { exists() }
                    jsonPath("$.registeredZones[1].name") { value("A") }
                    jsonPath("$.message") { value("이벤트 및 2개 구역 등록이 완료되었습니다.") }
                }.andReturn()

        val created =
            transaction {
                val eventRow = EventsTable.selectAll().single()

                CreatedEventAndZones(
                    eventId = eventRow[EventsTable.id],
                    eventPublicId = eventRow[EventsTable.publicId],
                    eventCount = EventsTable.selectAll().count(),
                    zones =
                        ZonesTable
                            .selectAll()
                            .map {
                                CreatedZone(
                                    id = it[ZonesTable.id],
                                    publicId = it[ZonesTable.publicId],
                                    totalCapacity = it[ZonesTable.totalCapacity],
                                )
                            },
                )
            }

        assertThat(created.eventCount).isEqualTo(1)
        assertThat(created.zones).hasSize(2)
        assertThat(created.zones.map { it.publicId }).allSatisfy { assertThat(it).isNotBlank() }
        assertRedisInitialized(created, result)
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

    @Test
    fun `구역 목록이 비어 있으면 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = createRequest(zones = "[]")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
            }

        assertEventAndZoneTablesAreEmpty()
    }

    @Test
    fun `필수 이벤트 정보가 비어 있으면 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    createRequest()
                        .replace("\"name\": \"2027 SnapTix Concert\"", "\"name\": \"\"")
                        .replace("\"location\": \"KSPO DOME\"", "\"location\": \"\"")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(ErrorCode.VALIDATION_FAILED.code) }
            }

        assertEventAndZoneTablesAreEmpty()
    }

    @Test
    fun `구역명이 비어 있으면 이벤트와 구역을 등록할 수 없다`() {
        mockMvc
            .post("/api/v1/admin/events") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    createRequest(
                        zones =
                            """
                            [
                              {
                                "name": "",
                                "unitPrice": 150000,
                                "totalCapacity": 100
                              }
                            ]
                            """.trimIndent(),
                    )
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
        zones: String = defaultZones(),
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
          "zones": $zones
        }
        """.trimIndent()

    private fun defaultZones(): String =
        """
        [
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

    private fun assertRedisInitialized(
        created: CreatedEventAndZones,
        result: MvcResult,
    ) {
        created.zones.forEach { zone ->
            val stockKey = "ZONE:${zone.id}:stock"

            assertThat(result.response.contentAsString).contains("\"zoneId\":\"${zone.publicId}\"")
            assertThat(result.response.contentAsString).contains("\"redisStockKey\":\"$stockKey\"")
            assertThat(redisTemplate.opsForValue().get(stockKey))
                .isEqualTo(zone.totalCapacity.toString())
        }

        // event:info는 JSON String(Cache-Aside 리더와 동일 포맷)으로 적재된다.
        val eventInfoKey = "event:info:${created.eventPublicId}"
        val cachedJson =
            requireNotNull(redisTemplate.opsForValue().get(eventInfoKey)) {
                "event:info 캐시가 적재되지 않았습니다: $eventInfoKey"
            }
        val cached = objectMapper.readValue(cachedJson, EventInfo::class.java)
        assertThat(cached.eventId).isEqualTo(created.eventPublicId)
        assertThat(cached.name).isEqualTo("2027 SnapTix Concert")
        assertThat(cached.startTime).isEqualTo("2027-12-25T10:00:00Z")
        assertThat(cached.endTime).isEqualTo("2027-12-25T13:00:00Z")
        assertThat(cached.status).isEqualTo("PENDING")
        assertThat(redisTemplate.getExpire(eventInfoKey))
            .isBetween(1L, 3600L)
        assertThat(redisTemplate.hasKey("event:info:${created.eventId}")).isFalse()

        assertThat(redisTemplate.hasKey("queue:order:${created.eventPublicId}")).isTrue()
        assertThat(redisTemplate.hasKey("queue:order:${created.eventId}")).isFalse()
    }

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).takeIf { it.isNotEmpty() }?.let(redisTemplate::delete)
    }

    private data class EventsAndZonesCount(
        val events: Long,
        val zones: Long,
    )

    private data class CreatedEventAndZones(
        val eventId: Long,
        val eventPublicId: String,
        val eventCount: Long,
        val zones: List<CreatedZone>,
    )

    private data class CreatedZone(
        val id: Long,
        val publicId: String,
        val totalCapacity: Int,
    )
}
