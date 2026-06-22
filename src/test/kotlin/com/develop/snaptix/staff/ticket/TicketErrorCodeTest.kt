package com.develop.snaptix.staff.ticket

import com.develop.snaptix.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus

class TicketErrorCodeTest {
    @ParameterizedTest
    @EnumSource(
        value = ErrorCode::class,
        names = [
            "EVENT_MISMATCH",
            "TICKET_ALREADY_USED",
        ],
    )
    fun `ticket error codes should map to conflict`(errorCode: ErrorCode) {
        assertThat(errorCode.status)
            .isEqualTo(HttpStatus.CONFLICT)
    }
}
