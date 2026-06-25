package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.domain.order.api.dto.OrderMessage
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.global.redis.resilience.ResilientRedisExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** XREADGROUP으로 읽은 Stream 메시지. */
data class StreamMessage(
    val id: String,
    val body: Map<String, String>,
)

/**
 * 주문 큐 Redis Stream 게이트웨이(생산·소비 핵심).
 *
 * 무손실 보장은 Consumer Group + PEL + XACK 수명주기에서 나온다. 블라인드 MAXLEN 트리밍은
 * 사용하지 않는다(미확인 PEL 엔트리 유실 방지). XAUTOCLAIM 회수·MINID 트림은 ISSUE-13에서 추가.
 *
 * 모든 호출은 [ResilientRedisExecutor]로 감싸 서킷·로깅을 일괄 적용한다.
 * (`ensureGroup`은 setup 연산이라 예외 — BUSYGROUP을 멱등 무시해야 하므로 executor 미경유)
 */
@Component
class OrderStreamGateway(
    private val redis: StringRedisTemplate,
    private val keys: RedisKeyFactory,
    private val executor: ResilientRedisExecutor,
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
            log.debug(e) { "consumer group already ensured: group=$group" }
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

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
