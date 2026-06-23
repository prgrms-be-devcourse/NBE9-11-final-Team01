package com.develop.snaptix.domain.event.dto

import com.develop.snaptix.global.common.dto.PageMetadataDto
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "이벤트 목록 조회 응답 문서화 모델")
data class EventListSwaggerResponse(
    @field:Schema(description = "이벤트 요약 목록")
    val content: List<EventSummaryDto>,
    @field:Schema(description = "페이징 메타데이터")
    val pageable: PageMetadataDto,
)
