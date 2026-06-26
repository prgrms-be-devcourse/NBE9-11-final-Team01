package com.develop.snaptix.domain.reservation.sse

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

abstract class OrderSseIntegrationSupport {
    companion object {
        private const val REDIS_PORT = 6379

        private val mysql: MySQLContainer =
            MySQLContainer("mysql:9.7").apply {
                withDatabaseName("snaptix")
                withUsername("snaptix")
                withPassword("snaptix1234")
                start()
            }

        private val redis: GenericContainer<*> =
            GenericContainer("redis:8.8.0").apply {
                withExposedPorts(REDIS_PORT)
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-order-sse-256bit" }
        }
    }
}
