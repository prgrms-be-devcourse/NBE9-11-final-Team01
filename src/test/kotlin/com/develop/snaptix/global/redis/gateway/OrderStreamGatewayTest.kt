package com.develop.snaptix.global.redis.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
class OrderStreamGatewayTest {
    @Autowired
    private lateinit var gateway: OrderStreamGateway

    @Test
    fun `XADD 적재 후 XLEN이 1이다`() {
        val eventId = UUID.randomUUID()

        gateway.add(eventId, mapOf("orderId" to "order-1"))

        assertThat(gateway.length(eventId)).isEqualTo(1L)
    }

    @Test
    fun `그룹 생성 후 소비하면 적재한 메시지를 읽는다`() {
        val eventId = UUID.randomUUID()
        gateway.ensureGroup(eventId, GROUP)
        val messageId = gateway.add(eventId, mapOf("orderId" to "order-1", "eventId" to eventId.toString()))

        val messages = gateway.read(eventId, GROUP, CONSUMER, READ_COUNT)

        assertThat(messages).hasSize(1)
        assertThat(messages.first().id).isEqualTo(messageId)
        assertThat(messages.first().body).containsEntry("orderId", "order-1")
    }

    @Test
    fun `ACK하면 확인 수 1을 반환하고 동일 컨슈머가 신규 소비 시 재배달되지 않는다`() {
        val eventId = UUID.randomUUID()
        gateway.ensureGroup(eventId, GROUP)
        val messageId = gateway.add(eventId, mapOf("orderId" to "order-1"))
        gateway.read(eventId, GROUP, CONSUMER, READ_COUNT)

        val acked = gateway.ack(eventId, GROUP, messageId)

        assertThat(acked).isEqualTo(1L)
        assertThat(gateway.read(eventId, GROUP, CONSUMER, READ_COUNT)).isEmpty()
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val READ_COUNT = 10
        private const val GROUP = "order-workers"
        private const val CONSUMER = "consumer-1"

        @Container
        @JvmStatic
        val mysql =
            MySQLContainer("mysql:9.7").apply {
                withDatabaseName("snaptix")
                withUsername("snaptix")
                withPassword("snaptix1234")
            }

        @Container
        @JvmStatic
        val redisContainer =
            GenericContainer("redis:8.8.0").apply {
                withExposedPorts(REDIS_PORT)
            }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(REDIS_PORT) }
        }
    }
}
