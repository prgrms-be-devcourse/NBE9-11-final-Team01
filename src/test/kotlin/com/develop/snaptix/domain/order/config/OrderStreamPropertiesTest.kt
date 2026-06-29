package com.develop.snaptix.domain.order.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderStreamPropertiesTest {
    @Test
    fun `Consumer Group 기본값은 order-workers이다`() {
        val properties = OrderStreamProperties()

        assertThat(properties.consumerGroup).isEqualTo(DEFAULT_CONSUMER_GROUP)
    }

    companion object {
        private const val DEFAULT_CONSUMER_GROUP = "order-workers"
    }
}
