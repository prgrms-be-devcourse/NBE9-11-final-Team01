package com.develop.snaptix.domain.event

import com.develop.snaptix.domain.event.dto.EventBulkCreateResponse
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.support.IntegrationTestSupport
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
class AdminEventPublicReflectionIntegrationTest(
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
        deleteRedisKeys("rate_limit:*")
    }

    @Test
    fun `관리자 생성 이벤트는 상태 변경에 따라 공개 조회에 반영된다`() {
        val created = createEvent()

        assertPublicListIsEmpty()
        assertPublicDetailNotFound(created.eventId)

        updateEventStatus(created.eventId, EventStatus.ON_SALE)

        mockMvc
            .get("/api/v1/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].eventId") { value(created.eventId) }
                jsonPath("$.content[0].name") { value("2027 SnapTix Admin Reflection") }
                jsonPath("$.content[0].status") { value("ON_SALE") }
                jsonPath("$.content[0].minPrice") { value(180_000) }
                jsonPath("$.content[0].isSoldOut") { value(false) }
            }

        mockMvc
            .get("/api/v1/events/${created.eventId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(created.eventId) }
                jsonPath("$.name") { value("2027 SnapTix Admin Reflection") }
                jsonPath("$.status") { value("ON_SALE") }
                jsonPath("$.zones.length()") { value(1) }
                jsonPath("$.zones[0].zoneId") { value(created.registeredZones[0].zoneId) }
                jsonPath("$.zones[0].name") { value("VIP") }
                jsonPath("$.zones[0].unitPrice") { value(180_000) }
                jsonPath("$.zones[0].totalCapacity") { value(100) }
                jsonPath("$.zones[0].currentStock") { value(100) }
            }

        updateEventStatus(created.eventId, EventStatus.CLOSED)

        assertPublicListIsEmpty()
        assertPublicDetailNotFound(created.eventId)
    }

    private fun createEvent(): EventBulkCreateResponse {
        val result =
            mockMvc
                .post("/api/v1/admin/events") {
                    with(user("admin").roles("ADMIN"))
                    contentType = MediaType.APPLICATION_JSON
                    content = createRequest()
                }.andExpect {
                    status { isCreated() }
                }.andReturn()

        return objectMapper.readValue(result.response.contentAsString, EventBulkCreateResponse::class.java)
    }

    private fun updateEventStatus(
        eventId: String,
        status: EventStatus,
    ) {
        mockMvc
            .patch("/api/v1/admin/events/$eventId/status") {
                with(user("admin").roles("ADMIN"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"${status.name}"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.eventId") { value(eventId) }
                jsonPath("$.status") { value(status.name) }
            }
    }

    private fun assertPublicListIsEmpty() {
        mockMvc
            .get("/api/v1/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(0) }
                jsonPath("$.pageable.totalElements") { value(0) }
            }
    }

    private fun assertPublicDetailNotFound(eventId: String) {
        mockMvc
            .get("/api/v1/events/$eventId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(ErrorCode.EVENT_NOT_FOUND.code) }
            }
    }

    private fun createRequest(): String =
        """
        {
          "name": "2027 SnapTix Admin Reflection",
          "description": "관리자 생성 이벤트 공개 조회 반영 테스트",
          "location": "KSPO DOME",
          "startTime": "2027-12-25T19:00:00+09:00",
          "endTime": "2027-12-25T22:00:00+09:00",
          "initialStatus": "PENDING",
          "posterUrl": "https://cdn.snaptix.kr/events/admin-reflection.jpg",
          "zones": [
            {
              "name": "VIP",
              "unitPrice": 180000,
              "totalCapacity": 100
            }
          ]
        }
        """.trimIndent()

    private fun deleteRedisKeys(pattern: String) {
        redisTemplate.keys(pattern).takeIf { it.isNotEmpty() }?.let(redisTemplate::delete)
    }
}
