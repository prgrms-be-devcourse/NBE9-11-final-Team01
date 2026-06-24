package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.controller.docs.AdminEventApiDocs
import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.dto.EventBulkCreateResponse
import com.develop.snaptix.domain.event.dto.EventStatusUpdateRequest
import com.develop.snaptix.domain.event.dto.EventStatusUpdateResponse
import com.develop.snaptix.domain.event.service.EventService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/events")
class AdminEventController(
    private val eventService: EventService,
) : AdminEventApiDocs {
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    override fun createEvent(
        @Valid
        @RequestBody
        request: EventBulkCreateRequest,
    ): ResponseEntity<EventBulkCreateResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(eventService.createEventWithZones(request))

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{eventId}/status")
    override fun updateEventStatus(
        @PathVariable eventId: String,
        @Valid
        @RequestBody
        request: EventStatusUpdateRequest,
    ): ResponseEntity<EventStatusUpdateResponse> = ResponseEntity
        .ok(eventService.updateEventStatus(eventId, request))
}
