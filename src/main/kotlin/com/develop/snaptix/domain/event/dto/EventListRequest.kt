package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.global.common.dto.PageRequestConstraints
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

private const val MAX_EVENT_LIST_PAGE = 1_000L

@Schema(description = "이벤트 목록 조회 쿼리 파라미터")
data class EventListRequest(
    @field:Schema(description = "페이지 번호. 0부터 시작", example = "0")
    @field:Min(0)
    @field:Max(MAX_EVENT_LIST_PAGE)
    val page: Int = PageRequestConstraints.DEFAULT_PAGE,
    @field:Schema(description = "페이지 크기", example = "20")
    @field:Min(1)
    @field:Max(PageRequestConstraints.MAX_SIZE)
    val size: Int = PageRequestConstraints.DEFAULT_SIZE,
    @field:Schema(description = "정렬 기준. startTime, createdAt, name 허용", example = "startTime")
    val sortBy: String = "startTime",
    @field:Schema(description = "정렬 방향. asc 또는 desc 허용", example = "asc")
    val sortDir: String = "asc",
    @field:Schema(description = "장소명 부분 검색", example = "서울")
    @field:Size(max = 100)
    val location: String? = null,
    @field:Schema(description = "조회 시작일. Asia/Seoul 기준 00:00:00 이상", example = "2027-12-25")
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val startDate: LocalDate? = null,
    @field:Schema(description = "조회 종료일. Asia/Seoul 기준 다음날 00:00:00 미만", example = "2027-12-31")
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val endDate: LocalDate? = null,
)
