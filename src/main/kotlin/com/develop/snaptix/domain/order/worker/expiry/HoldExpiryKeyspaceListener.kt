package com.develop.snaptix.domain.order.worker.expiry

import com.develop.snaptix.domain.order.worker.release.StockReleaseService
import com.develop.snaptix.domain.reservation.repository.ReservationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component

/**
 * ORDER_HOLD Keyspace 만료 이벤트 리스너 — **보조 수단** (이슈 #10, Story 8-1).
 *
 * Redis Keyspace Notification 설정(`notify-keyspace-events KEX`) 하에
 * `ORDER_HOLD:{orderId}` 키가 만료되면 즉시 릴리즈를 시도한다.
 *
 * ## 역할 한계 (왜 보조인가)
 * Redis 재시작·장애 시 만료 이벤트가 유실될 수 있어 정합성을 단독으로 보장하지 못한다.
 * **정합성 정답 소스는 [HoldExpiryWorker] 배치**이며, 이 리스너는 배치 주기(기본 1분) 사이의
 * 반응 지연을 줄이기 위한 Best-effort 보조다.
 *
 * ## 활성화 방법
 * 1. `application.yaml` 에 `order.hold.keyspace-listener.enabled: true` 설정
 * 2. Redis 서버에 `notify-keyspace-events KEX` 설정 (`redis.conf` 또는 `CONFIG SET`)
 * → 두 조건이 모두 충족되어야 정상 동작한다.
 *
 * ## TODO (완전 구현 시 필요한 작업)
 * - `ReservationRepository` 에 `findIdAndZoneIdByOrderId(orderId)` 추가
 *   (현재 `findByOrderId` 는 `id`·`zoneId` 를 반환하지 않아 `releaseIfPending` 직접 호출 불가)
 */
@Component
@ConditionalOnProperty(
    name = ["order.hold.keyspace-listener.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class HoldExpiryKeyspaceListener(
    private val reservationRepository: ReservationRepository,
    private val stockReleaseService: StockReleaseService,
    @Qualifier("keyspaceListenerContainer")
    listenerContainer: RedisMessageListenerContainer,
) : MessageListener {
    private val logger = KotlinLogging.logger {}

    init {
        listenerContainer.addMessageListener(this, PatternTopic(KEYSPACE_PATTERN))
        logger.info { "[HOLD_EXPIRY][KEYSPACE] 리스너 등록 완료: pattern=$KEYSPACE_PATTERN" }
    }

    /**
     * Redis Keyspace 만료 이벤트 수신.
     *
     * - [message.body]: 만료된 키 이름 (예: `ORDER_HOLD:550e8400-e29b-41d4-a716-446655440000`)
     * - [message.channel]: 이벤트 채널 (예: `__keyevent@0__:expired`)
     * ORDER_HOLD 키 외의 만료 이벤트는 조기 반환으로 무시한다.
     */
    @Suppress("TooGenericExceptionCaught") // 단일 이벤트 실패가 리스너를 중단하지 않도록 방어
    override fun onMessage(
        message: Message,
        pattern: ByteArray?,
    ) {
        val expiredKey = message.body.toString(Charsets.UTF_8)

        if (!expiredKey.startsWith(ORDER_HOLD_PREFIX)) return

        val orderId = expiredKey.removePrefix(ORDER_HOLD_PREFIX)
        logger.debug { "[HOLD_EXPIRY][KEYSPACE] 만료 이벤트 수신 — orderId=$orderId" }

        try {
            processExpiry(orderId)
        } catch (e: Exception) {
            // 실패해도 배치([HoldExpiryWorker])가 보완 — WARN 레벨로 기록
            logger.warn(e) { "[HOLD_EXPIRY][KEYSPACE] 처리 실패, 배치가 보완 — orderId=$orderId" }
        }
    }

    /**
     * 만료 처리 — TODO: 완전 구현 필요.
     *
     * 현재 [ReservationRepository.findByOrderId] 는 `id`·`zoneId` 를 포함하지 않아
     * [ReservationRepository.releaseIfPending] 직접 호출이 불가능하다.
     * `findIdAndZoneIdByOrderId` 추가 후 [HoldExpiryWorker.expireHeldOrders] 와 동일한
     * 조건부 UPDATE + [StockReleaseService.release] 패턴을 적용한다.
     *
     * ```kotlin
     * // 완전 구현 예시 (findIdAndZoneIdByOrderId 추가 후)
     * val reservation = reservationRepository.findIdAndZoneIdByOrderId(orderId) ?: return
     * val affected = reservationRepository.releaseIfPending(reservation.id)
     * if (affected == 1) {
     *     stockReleaseService.release(orderId, reservation.zoneId, ReleaseReason.PAYMENT_TIMEOUT)
     * }
     * ```
     */
    private fun processExpiry(orderId: String) {
        logger.info {
            "[HOLD_EXPIRY][KEYSPACE] 배치([HoldExpiryWorker])에 위임 — orderId=$orderId. " +
                "TODO: findIdAndZoneIdByOrderId 추가 후 직접 처리로 전환"
        }
        // TODO: ReservationRepository.findIdAndZoneIdByOrderId() 구현 후 활성화
        // val reservation = reservationRepository.findIdAndZoneIdByOrderId(orderId) ?: return
        // val affected = reservationRepository.releaseIfPending(reservation.id)
        // if (affected == 1) {
        //     stockReleaseService.release(orderId, reservation.zoneId, ReleaseReason.PAYMENT_TIMEOUT)
        // }
    }

    private companion object {
        const val KEYSPACE_PATTERN = "__keyevent@*__:expired"
        const val ORDER_HOLD_PREFIX = "ORDER_HOLD:"
    }
}
