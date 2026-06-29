package com.develop.snaptix.staff.ticket.logging

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class VerifyAuditLogger {
    private val log = LoggerFactory.getLogger(javaClass)

    fun warn(
        code: String,
        ticketCode: String? = null,
        eventId: String? = null,
        staffId: String? = null,
        traceId: String? = null,
    ) {
        log.warn(
            "action=TICKET_VERIFY result=FAIL code={} ticketCode={} eventId={} staffId={} traceId={}",
            code,
            ticketCode,
            eventId,
            staffId,
            traceId,
        )
    }
}
