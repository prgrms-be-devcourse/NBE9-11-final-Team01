package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.dto.EventDetailResponse
import com.develop.snaptix.domain.event.dto.EventResponse
import com.develop.snaptix.domain.event.dto.PageResponse
import com.develop.snaptix.domain.event.service.EventService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Event", description = "이벤트 조회 API (The Front Door)")
@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventService: EventService,
) {
    @Operation(summary = "이벤트 목록 조회", description = "진행 중이거나 예정된 이벤트 목록을 조건에 맞춰 페이징하여 조회합니다.")
    @GetMapping
    fun getEvents(
        @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
        @RequestParam(defaultValue = "0")
        page: Int,
        @Parameter(description = "페이지 크기", example = "20")
        @RequestParam(defaultValue = "20")
        size: Int,
        @Parameter(description = "정렬 기준 (startTime, createdAt, name)", example = "startTime")
        @RequestParam(defaultValue = "startTime")
        sortBy: String,
        @Parameter(description = "정렬 방향 (asc, desc)", example = "asc")
        @RequestParam(defaultValue = "asc")
        sortDir: String,
        @Parameter(description = "장소 필터링", required = false)
        @RequestParam(required = false)
        location: String?,
        @Parameter(description = "시작일 필터링 (yyyy-MM-dd)", required = false)
        @RequestParam(required = false)
        startDate: LocalDate?,
        @Parameter(description = "종료일 필터링 (yyyy-MM-dd)", required = false)
        @RequestParam(required = false)
        endDate: LocalDate?,
    ): ResponseEntity<PageResponse<EventResponse>> {
        val response =
            eventService.getEvents(
                page = page,
                size = size,
                sortBy = sortBy,
                sortDir = sortDir,
                location = location,
                startDate = startDate,
                endDate = endDate,
            )
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "이벤트 상세 조회", description = "특정 이벤트의 상세 정보와 실시간 잔여 수량을 조회합니다.")
    @GetMapping("/{eventId}")
    fun getEventDetail(
        @Parameter(description = "이벤트 공개 ID (UUID)", required = true)
        @PathVariable
        eventId: String,
    ): ResponseEntity<EventDetailResponse> {
        val response = eventService.getEventDetail(eventId)
        return ResponseEntity.ok(response)
    }
}
