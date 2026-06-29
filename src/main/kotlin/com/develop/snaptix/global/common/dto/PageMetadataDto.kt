package com.develop.snaptix.global.common.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "페이징 메타데이터")
data class PageMetadataDto(
    @field:Schema(description = "현재 페이지 번호 (0-based)", example = "0")
    val pageNumber: Int,
    @field:Schema(description = "페이지당 항목 수", example = "20")
    val pageSize: Int,
    @field:Schema(description = "전체 항목 수", example = "45")
    val totalElements: Long,
    @field:Schema(description = "전체 페이지 수", example = "3")
    val totalPages: Int,
)
