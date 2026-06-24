package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.dto.EventDetailResponse
import com.develop.snaptix.domain.event.dto.EventListRequest
import com.develop.snaptix.domain.event.dto.EventListSwaggerResponse
import com.develop.snaptix.domain.event.dto.EventSummaryDto
import com.develop.snaptix.domain.event.service.EventQueryService
import com.develop.snaptix.global.aop.annotation.RateLimit
import com.develop.snaptix.global.common.dto.PageResponse
import com.develop.snaptix.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val EVENT_LIST_VALIDATION_ERROR_EXAMPLE = """
{
  "code": "COMMON-009",
  "message": "입력값 검증에 실패했습니다.",
  "errors": [
    {
      "field": "size",
      "reason": "50 이하여야 합니다."
    }
  ]
}
"""

private const val EVENT_LIST_INVALID_SORT_EXAMPLE = """
{
  "code": "COMMON-002",
  "message": "sortBy는 startTime, createdAt, name만 허용됩니다.",
  "errors": null
}
"""

private const val EVENT_LIST_RATE_LIMIT_EXAMPLE = """
{
  "code": "COMMON-010",
  "message": "요청 횟수가 초과되었습니다. 잠시 후 다시 시도해주세요.",
  "errors": null
}
"""

private const val EVENT_NOT_FOUND_EXAMPLE = """
{
  "code": "EVENT-001",
  "message": "존재하지 않는 이벤트입니다.",
  "errors": null
}
"""

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
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = EventListSwaggerResponse::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "쿼리 파라미터 검증 실패 또는 허용되지 않는 정렬 조건",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(
                                name = "ValidationFailed",
                                value = EVENT_LIST_VALIDATION_ERROR_EXAMPLE,
                            ),
                            ExampleObject(
                                name = "InvalidSort",
                                value = EVENT_LIST_INVALID_SORT_EXAMPLE,
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "429",
                description = "동일 IP 기준 이벤트 목록 조회 요청 제한 초과",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(
                                name = "RateLimitExceeded",
                                value = EVENT_LIST_RATE_LIMIT_EXAMPLE,
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @RateLimit(limitPerSecond = 10, limitPerMinute = 600)
    fun getEvents(
        @Valid
        @ModelAttribute
        request: EventListRequest,
    ): ResponseEntity<PageResponse<EventSummaryDto>> = ResponseEntity.ok(eventQueryService.getEvents(request))

    @Operation(
        summary = "이벤트 상세 및 실시간 재고 조회",
        description =
            "이벤트 상세 정보와 구역별 재고 정보를 조회합니다. eventId와 zoneId는 외부 식별자인 public_id(UUID)를 사용합니다. " +
                "상세 조회는 ON_SALE, SOLD_OUT 상태 이벤트만 공개하며 currentStock은 Redis 재고를 우선 사용하고 누락 시 MySQL 기준으로 계산합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "이벤트 상세 조회 성공",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = EventDetailResponse::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 이벤트",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "EventNotFound", value = EVENT_NOT_FOUND_EXAMPLE)],
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/{eventId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getEventDetail(
        @Parameter(
            description = "조회할 이벤트 외부 식별자(events.public_id)",
            example = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        )
        @PathVariable eventId: String,
    ): ResponseEntity<EventDetailResponse> = ResponseEntity.ok(eventQueryService.getEventDetail(eventId))
}
