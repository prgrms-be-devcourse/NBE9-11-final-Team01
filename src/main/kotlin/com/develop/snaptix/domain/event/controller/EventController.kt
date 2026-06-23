package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.dto.EventSummaryDto
import com.develop.snaptix.domain.event.service.EventQueryService
import com.develop.snaptix.global.aop.annotation.RateLimit
import com.develop.snaptix.global.common.dto.PageResponse
import com.develop.snaptix.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Events", description = "이벤트 조회 API")
@Validated
@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventQueryService: EventQueryService,
) {
    @Operation(
        summary = "이벤트 목록 조회",
        description = "판매 중인 이벤트 목록을 조회합니다. status 파라미터는 받지 않으며 내부적으로 ON_SALE 이벤트만 반환합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "이벤트 목록 조회 성공",
                content = [Content(schema = Schema(implementation = PageResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "쿼리 파라미터 검증 실패 또는 허용되지 않는 정렬 조건",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping
    @RateLimit(limitPerSecond = 10, limitPerMinute = 600)
    fun getEvents(
        @Valid
        @ModelAttribute
        request: EventListRequest,
    ): ResponseEntity<PageResponse<EventSummaryDto>> = ResponseEntity.ok(eventQueryService.getEvents(request))
}
