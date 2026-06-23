package com.develop.snaptix.global.common.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

object PageRequestConstraints {
    const val DEFAULT_PAGE = 0
    const val DEFAULT_SIZE = 20
    const val MAX_SIZE = 50L
}

@Schema(description = "공통 페이징 요청")
data class PageRequestDto(
    @field:Schema(description = "페이지 번호 (0-based)", example = "0")
    @field:Min(0)
    val page: Int = PageRequestConstraints.DEFAULT_PAGE,
    @field:Schema(description = "페이지당 항목 수", example = "20")
    @field:Min(1)
    @field:Max(PageRequestConstraints.MAX_SIZE)
    val size: Int = PageRequestConstraints.DEFAULT_SIZE,
)
