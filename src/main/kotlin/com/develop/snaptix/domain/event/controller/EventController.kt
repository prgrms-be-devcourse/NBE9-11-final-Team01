package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.dto.EventDetailResponse
import com.develop.snaptix.domain.event.dto.EventResponse
import com.develop.snaptix.domain.event.dto.PageResponse
import com.develop.snaptix.domain.event.service.EventService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventService: EventService,
) {
    @GetMapping
    fun getEvents(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "startTime") sortBy: String,
        @RequestParam(defaultValue = "asc") sortDir: String,
        @RequestParam(required = false) location: String?,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
    ): ResponseEntity<PageResponse<EventResponse>> {
        val response = eventService.getEvents(
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

    @GetMapping("/{eventId}")
    fun getEventDetail(
        @PathVariable eventId: String,
    ): ResponseEntity<EventDetailResponse> {
        val response = eventService.getEventDetail(eventId)
        return ResponseEntity.ok(response)
    }
}