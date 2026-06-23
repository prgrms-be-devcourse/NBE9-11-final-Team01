package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.repository.EventInsertResult
import com.develop.snaptix.domain.zone.repository.ZoneInsertResult
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration

private const val ORDER_WORKERS_GROUP = "order-workers"
private val EVENT_INFO_CACHE_TTL: Duration = Duration.ofHours(1)

// 🐛 정합 수정: event:info를 Hash(HSET) → JSON String(SET)으로 적재한다.
// 리더(CacheAsideAspect / EventCacheRedisGateway)가 String GET + JSON 파싱이므로 동일 포맷으로 통일.
// 스트림 그룹·stock 시딩의 원자성과 실패 시 롤백(writtenKeys pcall)은 그대로 유지.
private val EVENT_REDIS_INITIALIZE_SCRIPT =
    DefaultRedisScript(
        """
        local writtenKeys = {}

        local function remember(key)
          table.insert(writtenKeys, key)
        end

        local ok, err = pcall(function()
          local ttlSeconds = tonumber(ARGV[1])
          local groupName = ARGV[2]
          local cacheJson = ARGV[3]

          redis.call('SET', KEYS[1], cacheJson)
          remember(KEYS[1])
          redis.call('EXPIRE', KEYS[1], ttlSeconds)

          local groupResult = redis.pcall('XGROUP', 'CREATE', KEYS[2], groupName, '$', 'MKSTREAM')
          if type(groupResult) == 'table' and groupResult.err then
            if not string.find(groupResult.err, 'BUSYGROUP') then
              error(groupResult.err)
            end
          else
            remember(KEYS[2])
          end

          local stockCount = tonumber(ARGV[4])
          local argIndex = 5

          for i = 1, stockCount do
            local stockKey = KEYS[i + 2]
            redis.call('SET', stockKey, ARGV[argIndex])
            remember(stockKey)
            argIndex = argIndex + 1
          end
        end)

        if not ok then
          for _, key in ipairs(writtenKeys) do
            redis.call('DEL', key)
          end
          error(err)
        end

        return 'OK'
        """.trimIndent(),
        String::class.java,
    )

@Component
class EventRedisInitializer(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun initialize(
        event: EventInsertResult,
        request: EventBulkCreateRequest,
        zones: List<ZoneInsertResult>,
    ) {
        redisTemplate.execute(
            EVENT_REDIS_INITIALIZE_SCRIPT,
            buildKeys(event, zones),
            *buildArguments(event, request, zones).toTypedArray(),
        )
    }

    fun stockKey(zoneId: Long): String = "ZONE:$zoneId:stock"

    private fun eventInfoKey(eventId: String): String = "event:info:$eventId"

    private fun orderStreamKey(eventId: String): String = "queue:order:$eventId"

    private fun buildKeys(
        event: EventInsertResult,
        zones: List<ZoneInsertResult>,
    ): List<String> = buildList {
        add(eventInfoKey(event.publicId))
        add(orderStreamKey(event.publicId))
        zones.forEach { add(stockKey(it.id)) }
    }

    private fun buildArguments(
        event: EventInsertResult,
        request: EventBulkCreateRequest,
        zones: List<ZoneInsertResult>,
    ): List<String> {
        val cacheJson = objectMapper.writeValueAsString(event.toEventInfo(request))

        return buildList {
            add(EVENT_INFO_CACHE_TTL.seconds.toString())
            add(ORDER_WORKERS_GROUP)
            add(cacheJson)
            add(zones.size.toString())
            zones.forEach { add(it.totalCapacity.toString()) }
        }
    }

    private fun EventInsertResult.toEventInfo(request: EventBulkCreateRequest): EventInfo = EventInfo(
        eventId = publicId,
        name = request.name,
        description = request.description.orEmpty(),
        location = request.location,
        startTime = request.startTime.toInstant().toString(),
        endTime = request.endTime.toInstant().toString(),
        status = request.initialStatus.name,
        posterUrl = request.posterUrl.orEmpty(),
    )
}
