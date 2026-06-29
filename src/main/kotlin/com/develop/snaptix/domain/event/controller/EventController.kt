package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.controller.docs.EventApiDocs
import com.develop.snaptix.domain.event.dto.EventDetailResponse
import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.dto.EventSummaryDto
import com.develop.snaptix.domain.event.service.EventQueryService
import com.develop.snaptix.global.aop.annotation.RateLimit
import com.develop.snaptix.global.common.dto.PageResponse
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventQueryService: EventQueryService,
) : EventApiDocs {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @RateLimit(limitPerSecond = 10, limitPerMinute = 600)
    override fun getEvents(
        @Valid
        @ModelAttribute
        request: EventListRequest,
    ): ResponseEntity<PageResponse<EventSummaryDto>> = ResponseEntity.ok(eventQueryService.getEvents(request))

    @GetMapping("/{eventId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    override fun getEventDetail(
        @PathVariable eventId: String,
    ): ResponseEntity<EventDetailResponse> = ResponseEntity.ok(eventQueryService.getEventDetail(eventId))
}
