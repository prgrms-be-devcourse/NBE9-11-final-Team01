package com.develop.snaptix.domain.order.scheduler

import com.develop.snaptix.domain.order.config.OrderStreamProperties
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * ACK 완료된 주문 Stream 메시지를 주기적으로 정리한다.
 *
 * PEL에 남은 미확인 메시지와 아직 전달되지 않은 메시지 보존은 [OrderStreamGateway.trimAcknowledged]가 담당한다.
 */
@Component
class OrderStreamTrimScheduler(
    private val targetRepository: OrderStreamTrimTargetRepository,
    private val orderStreamGateway: OrderStreamGateway,
    private val orderStreamProperties: OrderStreamProperties,
    @Value("\${order.stream.trim.enabled:true}") private val enabled: Boolean,
) {
    private val log = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedDelayString = "\${order.stream.trim.fixed-delay:3m}")
    fun trimAcknowledged() {
        if (!enabled) {
            return
        }

        val events =
            try {
                targetRepository.findTargets()
            } catch (e: RuntimeException) {
                log.warn(e) { "Failed to load active events for order stream trim" }
                return
            }

        events.forEach { event ->
            val eventPublicId =
                try {
                    UUID.fromString(event.eventPublicId)
                } catch (e: IllegalArgumentException) {
                    log.warn(e) { "Skip order stream trim because event publicId is invalid: eventId=${event.eventId}" }
                    return@forEach
                }

            trimEventStream(eventPublicId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun trimEventStream(eventPublicId: UUID) {
        try {
            val result = orderStreamGateway.trimAcknowledged(eventPublicId, orderStreamProperties.consumerGroup)
            if (result.trimmedCount > 0L) {
                log.info {
                    "Order stream trim completed: eventPublicId=$eventPublicId, " +
                        "trimmedCount=${result.trimmedCount}, minId=${result.minId}"
                }
            }
        } catch (e: RuntimeException) {
            log.warn(e) { "Order stream trim failed: eventPublicId=$eventPublicId" }
        }
    }
}
