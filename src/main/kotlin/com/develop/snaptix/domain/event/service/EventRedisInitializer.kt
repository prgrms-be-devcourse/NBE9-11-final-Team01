package com.develop.snaptix.domain.event.service

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.repository.EventInsertResult
import com.develop.snaptix.domain.zone.repository.ZoneInsertResult
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

private const val ORDER_WORKERS_GROUP = "order-workers"
private val EVENT_INFO_CACHE_TTL: Duration = Duration.ofHours(1)
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
          local fieldCount = tonumber(ARGV[3])
          local argIndex = 4

          for i = 1, fieldCount do
            redis.call('HSET', KEYS[1], ARGV[argIndex], ARGV[argIndex + 1])
            argIndex = argIndex + 2
          end
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

          local stockCount = tonumber(ARGV[argIndex])
          argIndex = argIndex + 1

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
        val cacheMap = event.toCacheMap(request)

        return buildList {
            add(EVENT_INFO_CACHE_TTL.seconds.toString())
            add(ORDER_WORKERS_GROUP)
            add(cacheMap.size.toString())
            cacheMap.forEach { (field, value) ->
                add(field)
                add(value)
            }
            add(zones.size.toString())
            zones.forEach { add(it.totalCapacity.toString()) }
        }
    }

    private fun EventInsertResult.toCacheMap(request: EventBulkCreateRequest): Map<String, String> = linkedMapOf(
        "eventId" to publicId,
        "name" to request.name,
        "description" to request.description.orEmpty(),
        "location" to request.location,
        "startTime" to request.startTime.toInstant().toString(),
        "endTime" to request.endTime.toInstant().toString(),
        "status" to request.initialStatus.name,
        "posterUrl" to request.posterUrl.orEmpty(),
    )
}
