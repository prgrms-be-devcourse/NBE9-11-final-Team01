package com.develop.snaptix.global.aop.aspect

import com.develop.snaptix.global.alert.model.AlertContext
import com.develop.snaptix.global.alert.model.AlertTrigger
import com.develop.snaptix.global.alert.service.AlertService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class RedisCircuitBreakerEventListener(
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val alertService: AlertService,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun registerListeners() {
        circuitBreakerRegistry
            .circuitBreaker("redis")
            .eventPublisher
            .onStateTransition { event ->
                val from = event.stateTransition.fromState.name
                val to = event.stateTransition.toState.name

                logger.atWarn {
                    message = "Redis circuit breaker state changed"
                    payload =
                        mapOf(
                            "action" to "CB_STATE_CHANGE",
                            "from" to from,
                            "to" to to,
                        )
                }

                if (event.stateTransition.toState == OPEN) {
                    alertService.notify(
                        AlertContext(
                            trigger = AlertTrigger.CIRCUIT_OPEN,
                            fields =
                                mapOf(
                                    "circuitName" to "redis",
                                    "from" to from,
                                    "to" to to,
                                ),
                        ),
                    )
                }
            }
    }
}
