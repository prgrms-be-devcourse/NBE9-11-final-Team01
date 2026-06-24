package com.develop.snaptix.global.common.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "공통 페이징 응답")
data class PageResponse<T>(
    @field:Schema(description = "현재 페이지의 데이터 목록")
    val content: List<T>,
    @field:Schema(description = "페이징 메타데이터")
    val pageable: PageMetadataDto,
) {
    companion object {
        fun <T> of(
            content: List<T>,
            pageNumber: Int,
            pageSize: Int,
            totalElements: Long,
        ): PageResponse<T> {
            require(pageSize > 0) { "pageSize는 1 이상이어야 합니다." }

            return PageResponse(
                content = content,
                pageable =
                    PageMetadataDto(
                        pageNumber = pageNumber,
                        pageSize = pageSize,
                        totalElements = totalElements,
                        totalPages = totalElements.toTotalPages(pageSize),
                    ),
            )
        }

        private fun Long.toTotalPages(pageSize: Int): Int {
            if (this == 0L) {
                return 0
            }

            return ((this + pageSize - 1) / pageSize).toInt()
        }
    }
}
