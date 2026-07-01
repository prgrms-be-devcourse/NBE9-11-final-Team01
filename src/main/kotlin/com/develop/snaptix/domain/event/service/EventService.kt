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
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val eventRedisKeyCleaner: EventRedisKeyCleaner,
    private val eventCacheGateway: EventCacheRedisGateway,
    private val redisKeyFactory: RedisKeyFactory,
) {
    private val logger = KotlinLogging.logger {}

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
                        redisStockKey = redisKeyFactory.stock(zone.id),
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
    ): EventStatusUpdateResponse {
        var cleanupTarget: EventRedisCleanupTarget? = null
        val response =
            transaction {
                val event =
                    eventRepository.findByPublicId(eventId)
                        ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
                val currentStatus = event.status.toEventStatus()

                validateStatusTransition(currentStatus, request.status)
                val updatedRows =
                    eventRepository.updateStatusByPublicId(
                        publicId = eventId,
                        currentStatus = currentStatus,
                        status = request.status,
                    )
                if (updatedRows != 1) {
                    throw BusinessException(ErrorCode.EVENT_STATUS_CONFLICT)
                }

                if (request.status == EventStatus.CLOSED) {
                    cleanupTarget =
                        EventRedisCleanupTarget(
                            eventPublicId = event.publicId,
                            zoneIds = zoneRepository.findIdsByEventId(event.id),
                        )
                }

                EventStatusUpdateResponse(
                    eventId = event.publicId,
                    status = request.status,
                    message = "이벤트 상태가 변경되었습니다.",
                )
            }

        // CLOSED 전이는 cleanupRedisKeys가 event:info 키 자체를 삭제하므로 별도 동기화가 불필요하다.
        // 그 외 전이(PENDING→ON_SALE, ON_SALE→SOLD_OUT)는 event:info 캐시의 status 필드가
        // 생성 시점 값으로 고정된 채 남아 있으므로, 여기서 캐시를 최신 상태로 맞춰준다.
        // (수정 전에는 이 경로가 없어 캐시가 TTL(1h) 동안 계속 과거 상태로 남고,
        //  OrderIngestService.validateEventStatus가 매번 "현재 판매 중인 이벤트가 아닙니다"로 거부했다.)
        val target = cleanupTarget
        if (target != null) {
            cleanupRedisKeys(target)
        } else {
            syncEventCache(eventId, request.status)
        }

        return response
    }

    /**
     * event:info 캐시의 status 필드만 최신 값으로 교체한다.
     * 다른 필드(name/description/location/시간/posterUrl/totalCapacity)는 이벤트 생성 이후
     * 불변이므로 기존 캐시 값을 그대로 유지하고 status만 갱신하면 충분하다(EventInfo 스키마 참고).
     *
     * 캐시가 이미 만료/미존재(get()이 null)라면 여기서 새로 채우지 않는다 — 다음 조회 시
     * cache miss로 처리되며, 이는 이 메서드가 다루는 "상태 동기화 누락" 문제와는 별개의
     * 캐시 미스/재구축(reconcile) 경로이기 때문이다.
     */
    private fun syncEventCache(
        eventId: String,
        status: EventStatus,
    ) {
        try {
            val eventPublicId = UUID.fromString(eventId)
            val cached = eventCacheGateway.get(eventPublicId) ?: return
            eventCacheGateway.put(eventPublicId, cached.copy(status = status.name))
        } catch (exception: DataAccessException) {
            logger.warn(exception) { "[EVENT_CACHE_SYNC_FAILED] eventPublicId=$eventId, status=$status" }
        }
    }

    private fun cleanupRedisKeys(target: EventRedisCleanupTarget) {
        try {
            eventRedisKeyCleaner.cleanup(target)
        } catch (exception: DataAccessException) {
            logger.warn(exception) { "[EVENT_REDIS_CLEANUP_FAILED] eventPublicId=${target.eventPublicId}" }
        }
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

    private fun String.toEventStatus(): EventStatus = runCatching { EventStatus.valueOf(this) }
        .getOrElse {
            throw BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, "저장된 이벤트 상태가 올바르지 않습니다. 현재 상태: $this")
        }
}
