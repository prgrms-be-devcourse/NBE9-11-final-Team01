package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.dto.EventBulkCreateResponse
import com.develop.snaptix.domain.event.dto.EventStatusUpdateRequest
import com.develop.snaptix.domain.event.dto.EventStatusUpdateResponse
import com.develop.snaptix.domain.event.dto.ZoneCreateResult
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.repository.EventInsertResult
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.zone.repository.ZoneCreateCommand
import com.develop.snaptix.domain.zone.repository.ZoneInsertResult
import com.develop.snaptix.domain.zone.repository.ZoneRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

private val ALLOWED_INITIAL_STATUSES = setOf(EventStatus.PENDING, EventStatus.ON_SALE)
private val ALLOWED_STATUS_TRANSITIONS =
    mapOf(
        EventStatus.PENDING to setOf(EventStatus.ON_SALE),
        EventStatus.ON_SALE to setOf(EventStatus.SOLD_OUT, EventStatus.CLOSED),
        EventStatus.SOLD_OUT to setOf(EventStatus.CLOSED),
    )

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val zoneRepository: ZoneRepository,
    private val eventRedisInitializer: EventRedisInitializer,
) {
    fun createEventWithZones(request: EventBulkCreateRequest): EventBulkCreateResponse {
        validateCreateRequest(request)

        return transaction {
            val event =
                eventRepository.insertEvent(
                    publicId = UUID.randomUUID().toString(),
                    name = request.name,
                    description = request.description,
                    location = request.location,
                    startTime = request.startTime.toInstant(),
                    endTime = request.endTime.toInstant(),
                    posterUrl = request.posterUrl,
                    status = request.initialStatus,
                )

            val zoneCommands =
                request.zones.map { zoneRequest ->
                    ZoneCreateCommand(
                        publicId = UUID.randomUUID().toString(),
                        name = zoneRequest.name,
                        unitPrice = zoneRequest.unitPrice,
                        totalCapacity = zoneRequest.totalCapacity,
                    )
                }
            val zones = zoneRepository.insertZones(event.id, zoneCommands)

            initializeRedis(event, request, zones)

            val registeredZones =
                zones.map { zone ->
                    ZoneCreateResult(
                        zoneId = zone.publicId,
                        name = zone.name,
                        unitPrice = zone.unitPrice,
                        totalCapacity = zone.totalCapacity,
                        redisStockKey = eventRedisInitializer.stockKey(zone.id),
                    )
                }

            EventBulkCreateResponse(
                eventId = event.publicId,
                eventName = request.name,
                status = request.initialStatus,
                registeredZones = registeredZones,
                message = "이벤트 및 ${registeredZones.size}개 구역 등록이 완료되었습니다.",
            )
        }
    }

    fun updateEventStatus(
        eventId: String,
        request: EventStatusUpdateRequest,
    ): EventStatusUpdateResponse =
        transaction {
            val event =
                eventRepository.findByPublicId(eventId)
                    ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
            val currentStatus = EventStatus.valueOf(event.status)

            validateStatusTransition(currentStatus, request.status)
            eventRepository.updateStatusByPublicId(eventId, request.status)

            EventStatusUpdateResponse(
                eventId = event.publicId,
                status = request.status,
                message = "이벤트 상태가 변경되었습니다.",
            )
        }

    private fun initializeRedis(
        event: EventInsertResult,
        request: EventBulkCreateRequest,
        zones: List<ZoneInsertResult>,
    ) {
        try {
            eventRedisInitializer.initialize(event, request, zones)
        } catch (exception: DataAccessException) {
            throw BusinessException(ErrorCode.EVENT_REDIS_INITIALIZATION_FAILED, cause = exception)
        }
    }

    private fun validateCreateRequest(request: EventBulkCreateRequest) {
        validateInitialStatus(request.initialStatus)
        validateEventTimes(request.startTime, request.endTime)
    }

    private fun validateInitialStatus(initialStatus: EventStatus) {
        if (initialStatus !in ALLOWED_INITIAL_STATUSES) {
            throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "초기 이벤트 상태는 PENDING 또는 ON_SALE만 허용됩니다.")
        }
    }

    private fun validateEventTimes(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
    ) {
        if (!endTime.toInstant().isAfter(startTime.toInstant())) {
            throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "이벤트 종료 시각은 시작 시각 이후여야 합니다.")
        }
    }

    private fun validateStatusTransition(
        currentStatus: EventStatus,
        nextStatus: EventStatus,
    ) {
        if (nextStatus !in ALLOWED_STATUS_TRANSITIONS[currentStatus].orEmpty()) {
            throw BusinessException(
                ErrorCode.INVALID_REQUEST_PARAMETER,
                "허용되지 않는 이벤트 상태 변경입니다. 현재 상태: $currentStatus, 요청 상태: $nextStatus",
            )
        }
    }
}
