package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.dto.EventBulkCreateResponse
import com.develop.snaptix.domain.event.dto.ZoneCreateResult
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.repository.EventRepository
import com.develop.snaptix.domain.zone.repository.ZoneRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

private val ALLOWED_INITIAL_STATUSES = setOf(EventStatus.PENDING, EventStatus.ON_SALE)

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val zoneRepository: ZoneRepository,
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

            val registeredZones =
                request.zones.map { zoneRequest ->
                    val zone =
                        zoneRepository.insertZone(
                            publicId = UUID.randomUUID().toString(),
                            eventId = event.id,
                            name = zoneRequest.name,
                            unitPrice = zoneRequest.unitPrice,
                            totalCapacity = zoneRequest.totalCapacity,
                        )

                    ZoneCreateResult(
                        zoneId = zone.publicId,
                        name = zone.name,
                        unitPrice = zone.unitPrice,
                        totalCapacity = zone.totalCapacity,
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
}
