package com.develop.snaptix.domain.reservation.sse

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

private const val REDIS_PORT = 6379

@Import(OrderSseIntegrationSupport.OrderSseContainerConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class OrderSseIntegrationSupport {
    @TestConfiguration(proxyBeanMethods = false)
    class OrderSseContainerConfig {
        @Bean(initMethod = "start", destroyMethod = "stop")
        fun orderSseMysqlContainer(): MySQLContainer = MySQLContainer("mysql:9.7").apply {
            withDatabaseName("snaptix")
            withUsername("snaptix")
            withPassword("snaptix1234")
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        fun orderSseRedisContainer(): GenericContainer<*> = GenericContainer("redis:8.8.0").apply {
            withExposedPorts(REDIS_PORT)
        }

        @Bean
        fun orderSseDynamicProperties(
            mysql: MySQLContainer,
            @Qualifier("orderSseRedisContainer")
            redis: GenericContainer<*>,
        ): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
        }
    }
}
