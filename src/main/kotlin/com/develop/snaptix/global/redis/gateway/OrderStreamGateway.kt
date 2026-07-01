package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import com.fasterxml.jackson.core.JsonProcessingException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** XREADGROUP으로 읽은 Stream 메시지. */
data class StreamMessage(
    val id: String,
    val body: Map<String, String>,
)

/** ACK 완료 메시지 trim 결과. */
data class StreamTrimResult(
    val trimmedCount: Long,
    val minId: String?,
)

/** XAUTOCLAIM 실행 결과. */
data class ClaimResult(
    val claimedMessages: List<StreamMessage>,
    val deletedIds: List<String>,
    val nextStartId: String,
)

/**
 * 주문 큐 Redis Stream 게이트웨이(생산·소비 핵심).
 *
 * 무손실 보장은 Consumer Group + PEL + XACK 수명주기에서 나온다. 블라인드 MAXLEN 트리밍은
 * 사용하지 않는다(미확인 PEL 엔트리 유실 방지). XAUTOCLAIM 회수는 후속 작업에서 추가한다.
 *
 * 모든 호출은 [ResilientRedisExecutor]로 감싸 서킷·로깅을 일괄 적용한다.
 * (`ensureGroup`은 setup 연산이라 예외 — BUSYGROUP을 멱등 무시해야 하므로 executor 미경유)
 */
@Component
class OrderStreamGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val executor: ResilientRedisExecutor,
    private val objectMapper: ObjectMapper,
    @Qualifier("xAutoClaimScript") private val xAutoClaimScript: RedisScript<String>,
) {
    /** XADD 적재. 생성된 RecordId 문자열을 반환한다(payload에 orderId 포함). */
    fun add(message: OrderMessage): String = executor.execute(RedisAction.QUEUE_XADD) {
        redis
            .opsForStream<String, String>()
            .add(keys.queueOrder(message.eventId), message.toStreamPayload())
            ?.value ?: error("XADD가 RecordId를 반환하지 않았습니다.")
    }

    /** XLEN — 인게스트 백프레셔(`XLEN ≥ 정원+α`) 판정용. */
    fun length(eventPublicId: UUID): Long = executor.execute(RedisAction.INGEST_BACKPRESSURE) {
        redis.opsForStream<String, String>().size(keys.queueOrder(eventPublicId)) ?: 0L
    }

    /**
     * XPENDING 전체 건수 — 현재 PEL 적체 깊이 게이지 갱신용.
     *
     * 스트림 키가 없거나 그룹이 아직 생성되지 않은 경우 0을 반환한다.
     */
    fun pendingCount(
        eventPublicId: UUID,
        group: String,
    ): Long = executor.execute(RedisAction.STREAM_DEPTH_CHECK) {
        val streamKey = keys.queueOrder(eventPublicId)
        if (redis.hasKey(streamKey) != true) return@execute 0L
        redis
            .opsForStream<String, String>()
            .pending(streamKey, group)
            ?.totalPendingMessages ?: 0L
    }

    /**
     * XGROUP CREATE(MKSTREAM). 그룹이 이미 있으면(BUSYGROUP) 멱등 무시한다.
     * createGroup은 MKSTREAM이므로 스트림이 없으면 함께 생성한다.
     */
    fun ensureGroup(
        eventPublicId: UUID,
        group: String,
    ) {
        try {
            redis
                .opsForStream<String, String>()
                .createGroup(keys.queueOrder(eventPublicId), ReadOffset.from("0"), group)
        } catch (e: DataAccessException) {
            if (e.isBusyGroup()) {
                log.debug(e) { "consumer group already ensured: group=$group" }
            } else {
                throw e
            }
        }
    }

    /** XREADGROUP — 신규 메시지 소비(PEL 기록). */
    fun read(
        eventPublicId: UUID,
        group: String,
        consumer: String,
        count: Int,
    ): List<StreamMessage> = executor.execute(RedisAction.XREADGROUP) {
        val records =
            redis.opsForStream<String, String>().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count.toLong()),
                StreamOffset.create(keys.queueOrder(eventPublicId), ReadOffset.lastConsumed()),
            )
        records.orEmpty().map { StreamMessage(it.id.value, it.value) }
    }

    /** XACK — 처리 완료 확인. 확인된 메시지 수를 반환한다. */
    fun ack(
        eventPublicId: UUID,
        group: String,
        messageId: String,
    ): Long = executor.execute(RedisAction.XACK) {
        redis
            .opsForStream<String, String>()
            .acknowledge(keys.queueOrder(eventPublicId), group, messageId) ?: 0L
    }

    /**
     * XTRIM MINID — PEL에 남은 메시지는 보존하고 ACK 완료 메시지만 정리한다.
     *
     * - PEL이 있으면 가장 오래된 pending ID보다 오래된 엔트리만 삭제한다.
     * - PEL이 없으면 group의 last-delivered-id까지 처리 완료된 것으로 보고 그 다음 ID를 기준으로 삭제한다.
     */
    fun trimAcknowledged(
        eventPublicId: UUID,
        group: String,
    ): StreamTrimResult = executor.execute(RedisAction.MINID_TRIM) {
        val streamKey = keys.queueOrder(eventPublicId)
        val minId = safeTrimMinId(streamKey, group) ?: return@execute StreamTrimResult(0L, null)
        val trimmedCount =
            redis.execute { connection ->
                connection.streamCommands().xTrim(
                    streamKey.toByteArray(StandardCharsets.UTF_8),
                    RedisStreamCommands.XTrimOptions.trim(
                        RedisStreamCommands.TrimOptions.minId(RecordId.of(minId)).exact(),
                    ),
                )
            } ?: 0L

        StreamTrimResult(trimmedCount = trimmedCount, minId = minId)
    }

    /**
     * XAUTOCLAIM — 죽거나 느린 워커의 미확인(PEL) 메시지를 회수합니다.
     *
     * ## 구현 방식
     * `connection.execute("XAUTOCLAIM", ...)` 는 Lettuce의 `RawListOutput`이 중첩 배열을
     * 평탄화(flatten)하는 문제로 파싱이 불가능합니다. (예: `[nextId, [msgs], [deleted]]` →
     * `[nextId, msgId, field, value]` 로 뭉개짐)
     *
     * 대신 Lua 스크립트에서 `redis.call('XAUTOCLAIM', ...)`을 실행하고 `cjson.encode()`로
     * 중첩 응답 전체를 하나의 JSON 문자열로 직렬화해 반환합니다.
     * Spring은 단일 `String`을 받으므로 중첩 파싱 문제를 완전히 우회합니다.
     */
    fun claim(
        eventPublicId: UUID,
        group: String,
        consumer: String,
        minIdleTime: java.time.Duration,
        startId: String = INITIAL_RECORD_ID,
        count: Int = 100,
    ): ClaimResult = executor.execute(RedisAction.CLAIM_REPROCESS) {
        val json =
            redis.execute(
                xAutoClaimScript,
                listOf(keys.queueOrder(eventPublicId)),
                group,
                consumer,
                minIdleTime.toMillis().toString(),
                startId,
                count.toString(),
            ) ?: return@execute ClaimResult(emptyList(), emptyList(), startId)

        parseClaimResult(json, startId)
    }

    /**
     * cjson.encode(raw) 결과를 파싱합니다.
     *
     * 예상 JSON 구조:
     * `[nextId, [[msgId, [f1,v1,f2,v2,...]], ...], [deletedId, ...]]`
     */
    private fun parseClaimResult(
        json: String,
        fallbackStartId: String,
    ): ClaimResult {
        val root =
            try {
                objectMapper.readTree(json)
            } catch (e: JsonProcessingException) {
                log.error(e) { "XAUTOCLAIM 응답 파싱 실패: json=$json" }
                return ClaimResult(emptyList(), emptyList(), fallbackStartId)
            }

        val nextStartId = root[0]?.asString(fallbackStartId) ?: fallbackStartId

        val claimedMessages =
            buildList {
                root[1]?.forEach { msgNode ->
                    val id = msgNode[0]?.asString() ?: return@forEach
                    val kvNode: JsonNode = msgNode[1] ?: return@forEach
                    val kvList = kvNode.toList()
                    val body = mutableMapOf<String, String>()
                    var i = 0
                    while (i + 1 < kvList.size) {
                        body[kvList[i].asString()] = kvList[i + 1].asString()
                        i += 2
                    }
                    add(StreamMessage(id, body))
                }
            }

        val deletedIds =
            buildList {
                root[2]?.forEach { add(it.asString()) }
            }

        return ClaimResult(claimedMessages, deletedIds, nextStartId)
    }

    private fun safeTrimMinId(
        streamKey: String,
        group: String,
    ): String? {
        if (redis.hasKey(streamKey) != true) {
            return null
        }

        val groupInfo =
            redis
                .opsForStream<String, String>()
                .groups(streamKey)
                .firstOrNull { it.groupName() == group }

        return groupInfo?.let {
            val pendingSummary = redis.opsForStream<String, String>().pending(streamKey, group)
            when {
                pendingSummary.totalPendingMessages > 0L -> pendingSummary.minMessageId()
                // XTRIM MINID는 기준 ID 미만만 삭제하므로 ACK 완료된 last-delivered-id까지 지우기 위해 다음 ID를 사용한다.
                else -> nextRecordId(it.lastDeliveredId())
            }
        }
    }

    private fun nextRecordId(recordId: String): String? {
        if (recordId == INITIAL_RECORD_ID) {
            return null
        }

        val parts = recordId.split(RECORD_ID_SEPARATOR)
        val timestamp = parts.getOrNull(0)?.toLongOrNull()
        val sequence = parts.getOrNull(1)?.toLongOrNull()

        return if (parts.size == RECORD_ID_PARTS && timestamp != null && sequence != null) {
            "$timestamp$RECORD_ID_SEPARATOR${sequence + 1}"
        } else {
            null
        }
    }

    private fun DataAccessException.isBusyGroup(): Boolean = listOfNotNull(message, mostSpecificCause.message)
        .any { it.contains(BUSYGROUP_ERROR, ignoreCase = true) }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val BUSYGROUP_ERROR = "BUSYGROUP"
        private const val INITIAL_RECORD_ID = "0-0"
        private const val RECORD_ID_SEPARATOR = "-"
        private const val RECORD_ID_PARTS = 2
    }
}
