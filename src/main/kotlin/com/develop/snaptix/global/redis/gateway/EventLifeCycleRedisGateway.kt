package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import com.develop.snaptix.global.redis.script.EVENT_REDIS_INITIALIZE_SCRIPT
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

data class StreamGroupInfo(
    val lastDeliveredId: String?,
    val pendingCount: Long,
)

@Component
class EventLifeCycleRedisGateway(
    private val redis: StringRedisTemplate,
    private val executor: ResilientRedisExecutor,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val initializeScript: RedisScript<String> =
        DefaultRedisScript(EVENT_REDIS_INITIALIZE_SCRIPT, String::class.java)

    /**
     * Lua 스크립트를 원자적으로 실행하여 이벤트 생성 라이프사이클 인프라를 시딩한다.
     */
    fun initializeEventInfrastructure(
        keys: List<String>,
        arguments: List<String>,
    ): String? = executor.execute(RedisAction.QUEUE_XADD) {
        redis.execute(initializeScript, keys, *arguments.toTypedArray())
    }

    /**
     * 이벤트 라이프사이클 종료에 따른 인프라 다중 키 삭제 무효화 연산. (CLOSED 정리)
     * @return 삭제에 성공한 키의 총 개수
     */
    fun deleteImmediateKeys(keys: List<String>): Long {
        if (keys.isEmpty()) return 0L
        return executor.execute(RedisAction.CACHE_INVALIDATE) {
            redis.delete(keys)
        } ?: 0L
    }

    /**
     * 특정 주문 Stream의 메시지 길이(XLEN)를 조회한다.
     */
    fun getStreamLength(streamKey: String): Long = try {
        executor.execute(RedisAction.XREADGROUP) {
            redis.opsForStream<String, String>().size(streamKey) ?: 0L
        }
    } catch (e: DataAccessException) {
        // ✅ 수정: 예외 전파를 막고 디버깅 로그 기록 후 0L 폴백
        logger.warn(e) { "[LIFECYCLE_GATEWAY_ERROR] Failed to fetch stream length for key=$streamKey" }
        0L
    } catch (e: RedisUnavailableException) {
        // ✅ 수정: 서킷 브레이커 개방 시에도 안전하게 0L 폴백
        logger.warn(e) { "[LIFECYCLE_GATEWAY_CIRCUIT_OPEN] Redis unavailable while fetching stream length" }
        0L
    }

    /**
     * 특정 주문 Stream의 Consumer Group 정보 중 지정된 그룹의 마지막 전달된 ID(lastDeliveredId)를 확인한다.
     * Detekt TooGenericExceptionCaught 방지를 위해 예외를 개별 전파합니다.
     */
    fun getGroupLastDeliveredId(
        streamKey: String,
        groupName: String,
    ): String? = try {
        executor.execute(RedisAction.XREADGROUP) {
            redis
                .opsForStream<String, String>()
                .groups(streamKey)
                .firstOrNull { it.groupName() == groupName }
                ?.lastDeliveredId()
        }
    } catch (e: DataAccessException) {
        logger.warn(e) { "[LIFECYCLE_GATEWAY_ERROR] Failed to fetch stream last delivered ID for key=$streamKey" }
        null
    } catch (e: RedisUnavailableException) {
        logger.warn(e) { "[LIFECYCLE_GATEWAY_CIRCUIT_OPEN] Redis unavailable while fetching last delivered ID" }
        null
    }

    /**
     * 특정 주문 Stream의 정보(StreamInfo)에서 가장 마지막으로 생성된 ID(lastGeneratedId)를 확인한다.
     */
    fun getStreamLastGeneratedId(streamKey: String): String? = try {
        executor.execute(RedisAction.XREADGROUP) {
            redis
                .opsForStream<String, String>()
                .info(streamKey)
                ?.lastGeneratedId()
        }
    } catch (e: DataAccessException) {
        logger.warn(e) { "[LIFECYCLE_GATEWAY_ERROR] Failed to fetch stream last generated ID for key=$streamKey" }
        null
    } catch (e: RedisUnavailableException) {
        logger.warn(e) { "[LIFECYCLE_GATEWAY_CIRCUIT_OPEN] Redis unavailable while fetching last generated ID" }
        null
    }

    /**
     * 특정 주문 Stream의 Consumer Group 정보(lastDeliveredId, pendingCount)를 확인한다.
     * 🐛 수정: PEL(Pending Entries List)에 남은 메시지 개수까지 한 번에 조회합니다.
     */
    fun getStreamGroupInfo(
        streamKey: String,
        groupName: String,
    ): StreamGroupInfo? = try {
        executor.execute(RedisAction.XREADGROUP) {
            redis
                .opsForStream<String, String>()
                .groups(streamKey)
                .firstOrNull { it.groupName() == groupName }
                ?.let {
                    StreamGroupInfo(
                        lastDeliveredId = it.lastDeliveredId(),
                        pendingCount = it.pendingCount(),
                    )
                }
        }
    } catch (e: DataAccessException) {
        logger.warn(e) { "[LIFECYCLE_GATEWAY_ERROR] Failed to fetch stream group info for group=$groupName" }
        null
    } catch (e: RedisUnavailableException) {
        logger.warn(e) { "[LIFECYCLE_GATEWAY_CIRCUIT_OPEN] Redis unavailable while fetching group info" }
        null
    }
}
