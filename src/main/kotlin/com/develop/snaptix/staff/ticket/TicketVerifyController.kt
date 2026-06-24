package com.develop.snaptix.staff.ticket

import com.develop.snaptix.staff.ticket.dto.TicketVerifyRequest
import com.develop.snaptix.staff.ticket.dto.TicketVerifyResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/staff/tickets")
class TicketVerifyController(
    private val service: TicketVerifyService,
) {

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('STAFF','ADMIN')")
    fun verify(
        @Valid @RequestBody request: TicketVerifyRequest,
    ): ResponseEntity<TicketVerifyResponse> =
        ResponseEntity.ok(service.verify(request))
}