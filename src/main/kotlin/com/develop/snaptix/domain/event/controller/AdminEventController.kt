package com.develop.snaptix.domain.event.controller

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.dto.EventBulkCreateResponse
import com.develop.snaptix.domain.event.dto.EventStatusUpdateRequest
import com.develop.snaptix.domain.event.dto.EventStatusUpdateResponse
import com.develop.snaptix.domain.event.service.EventService
import com.develop.snaptix.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val VALIDATION_ERROR_EXAMPLE = """
{
  "code": "COMMON-009",
  "message": "입력값 검증에 실패했습니다.",
  "errors": [
    {
      "field": "name",
      "reason": "이벤트명은 필수입니다."
    }
  ]
}
"""

private const val INVALID_STATUS_TRANSITION_EXAMPLE = """
{
  "code": "COMMON-002",
  "message": "허용되지 않는 이벤트 상태 변경입니다. 현재 상태: PENDING, 요청 상태: CLOSED",
  "errors": null
}
"""

private const val UNAUTHORIZED_EXAMPLE = """
{
  "code": "AUTH-006",
  "message": "인증이 필요합니다.",
  "errors": null
}
"""

private const val FORBIDDEN_EXAMPLE = """
{
  "code": "AUTH-005",
  "message": "접근 권한이 없습니다.",
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

private const val EVENT_STATUS_CONFLICT_EXAMPLE = """
{
  "code": "EVENT-005",
  "message": "이벤트 상태가 변경되어 요청을 처리할 수 없습니다.",
  "errors": null
}
"""

private const val EVENT_REDIS_INITIALIZATION_FAILED_EXAMPLE = """
{
  "code": "EVENT-004",
  "message": "Redis 초기화에 실패하여 이벤트를 생성할 수 없습니다.",
  "errors": null
}
"""

@Tag(name = "Admin Events", description = "관리자 이벤트 관리 API")
@RestController
@RequestMapping("/api/v1/admin/events")
class AdminEventController(
    private val eventService: EventService,
) {
    @Operation(
        summary = "이벤트 및 구역 Bulk 등록",
        description =
            "관리자가 이벤트와 1개 이상의 구역을 함께 등록합니다. " +
                "이벤트 publicId와 구역 publicId를 외부 식별자로 반환하며, Redis 초기 재고 키도 함께 초기화합니다.",
        security = [SecurityRequirement(name = "accessToken")],
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "이벤트 및 구역 등록 성공",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = EventBulkCreateResponse::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패 또는 허용되지 않는 초기 상태",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "ValidationFailed", value = VALIDATION_ERROR_EXAMPLE)],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_EXAMPLE)],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 없음",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "Forbidden", value = FORBIDDEN_EXAMPLE)],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "503",
                description = "Redis 초기화 실패로 이벤트 생성 불가",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(
                                name = "RedisInitializationFailed",
                                value = EVENT_REDIS_INITIALIZATION_FAILED_EXAMPLE,
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    fun createEvent(
        @Valid
        @RequestBody
        request: EventBulkCreateRequest,
    ): ResponseEntity<EventBulkCreateResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(eventService.createEventWithZones(request))

    @Operation(
        summary = "이벤트 상태 변경",
        description =
            "관리자가 이벤트 상태를 변경합니다. 허용 전이는 PENDING→ON_SALE, " +
                "ON_SALE→SOLD_OUT/CLOSED, SOLD_OUT→CLOSED입니다. CLOSED 전환 시 Redis 운영 키 정리를 시도합니다.",
        security = [SecurityRequirement(name = "accessToken")],
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "이벤트 상태 변경 성공",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = EventStatusUpdateResponse::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "존재하지 않는 상태값 또는 허용되지 않는 상태 전이",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(
                                name = "InvalidStatusTransition",
                                value = INVALID_STATUS_TRANSITION_EXAMPLE,
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_EXAMPLE)],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 없음",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [ExampleObject(name = "Forbidden", value = FORBIDDEN_EXAMPLE)],
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
            ApiResponse(
                responseCode = "409",
                description = "동시 상태 변경으로 조건부 업데이트 실패",
                content = [
                    Content(
                        mediaType = "application/json",
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(
                                name = "EventStatusConflict",
                                value = EVENT_STATUS_CONFLICT_EXAMPLE,
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{eventId}/status")
    fun updateEventStatus(
        @PathVariable eventId: String,
        @Valid
        @RequestBody
        request: EventStatusUpdateRequest,
    ): ResponseEntity<EventStatusUpdateResponse> = ResponseEntity
        .ok(eventService.updateEventStatus(eventId, request))
}
