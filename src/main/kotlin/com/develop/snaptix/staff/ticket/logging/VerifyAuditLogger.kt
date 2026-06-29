package com.develop.snaptix.staff.ticket.logging

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class VerifyAuditLogger {

    private val log = LoggerFactory.getLogger(javaClass)

    fun verifyFailed(
        code: String,
        ticketCode: String?,
        eventId: String?,
        staffId: Long?,
    ) {
        log.warn(
            "action=TICKET_VERIFY result=FAIL code={} ticketCode={} eventId={} staffId={}",
            code,
            ticketCode,
            eventId,
            staffId,
        )
    }
}
