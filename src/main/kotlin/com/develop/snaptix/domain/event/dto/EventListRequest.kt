package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.global.common.dto.PageRequestConstraints
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

private const val MAX_EVENT_LIST_PAGE = 1_000L

data class EventListRequest(
    @field:Min(0)
    @field:Max(MAX_EVENT_LIST_PAGE)
    val page: Int = PageRequestConstraints.DEFAULT_PAGE,
    @field:Min(1)
    @field:Max(PageRequestConstraints.MAX_SIZE)
    val size: Int = PageRequestConstraints.DEFAULT_SIZE,
    val sortBy: String = "startTime",
    val sortDir: String = "asc",
    @field:Size(max = 100)
    val location: String? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val startDate: LocalDate? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val endDate: LocalDate? = null,
)
