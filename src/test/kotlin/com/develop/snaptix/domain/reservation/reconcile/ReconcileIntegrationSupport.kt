package com.develop.snaptix.domain.reservation.reconcile

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

/**
 * Reconcile/Rebuild/Drift 통합 테스트 공통 컨테이너(싱글톤).
 * `@Container` 라이프사이클 상속 이슈를 피하려 companion init에서 직접 start()하고 JVM 종료까지 유지(Ryuk 정리).
 */
abstract class ReconcileIntegrationSupport {
    companion object {
        private const val REDIS_PORT = 6379

        /** Testcontainer 버전이슈 self-type 파라미터 X(추론으로) */
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
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) } // redis/REDIS_PORT 일치
            registry.add("jwt.secret") { "integration-test-secret-key-for-snaptix-reconcile-rebuild-256bit" }
        }
    }
}
