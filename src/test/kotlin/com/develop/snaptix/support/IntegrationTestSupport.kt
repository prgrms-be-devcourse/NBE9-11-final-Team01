package com.develop.snaptix.support

import com.develop.snaptix.domain.auditlog.entity.AuditLogsTable
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import com.develop.snaptix.domain.ticket.entity.TicketsTable
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

/**
 * 모든 Spring 통합 테스트(@SpringBootTest)의 단일 공통 베이스.
 *
 * 설계 근거 — Testcontainers 공식 "Singleton containers" 패턴:
 *  https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers
 *
 * ## 두 개의 축을 분리한다
 *  1) 컨테이너 축: companion 에서 한 번만 start() → JVM 종료까지 유지, 종료 시 Ryuk 가 정리.
 *     - 공식 문서가 "확장 기능의 특별 지원 없음, 이 패턴으로 하라"고 명시한 방식이다.
 *     - 따라서 @Testcontainers / @Container 는 **사용하지 않는다**. (섞으면 컨테이너가 2벌 뜬다)
 *  2) Spring 축: @SpringBootTest 는 컨텍스트만 띄우고 @DynamicPropertySource 로 컨테이너 포트를 주입한다.
 *
 * ## @SpringBootTest 를 베이스에 둔 이유
 *  - 자식 테스트가 컨텍스트 설정을 상속받아 중복 선언이 사라진다(추상 클래스라 단독 실행 안 됨).
 *  - 설정 키가 동일하면 Spring TestContext 가 **컨텍스트 1개를 캐시·재사용** → 전 통합 테스트가
 *    컨테이너 1세트 + 컨텍스트 1개를 공유하여 매우 빠르다.
 *  - IntelliJ 가 이 클래스를 Spring 테스트 컨텍스트로 인식하여
 *    `@Autowired members must be defined in valid Spring bean` 경고가 사라진다.
 *  - 주의: 자식이 @AutoConfigureMockMvc/@MockBean/@TestPropertySource/프로파일/webEnvironment 등을
 *    추가하면 캐시 키가 달라져 별도 컨텍스트가 생긴다(동작은 정상, 속도만 손해). 가능하면 설정을 통일한다.
 *
 * ## 격리(clean) — 매 테스트 전(@BeforeEach)
 *  - DB: FK 자식 → 부모 순서로 deleteAll
 *  - Redis: FLUSHDB (일반 키 + Stream + Consumer Group 일괄 제거)
 *
 * ## 제약
 *  - 공식 문서 명시: Testcontainers 는 **순차 실행만 지원**. 통합 테스트 병렬 실행 비활성 유지.
 *  - 순수 단위 테스트(RedisKeyFactoryTest, CacheAsideAspectTest, IdempotencyAspectTest,
 *    OrderSseAdapterTest 등 mockk 기반)는 컨테이너가 불필요하므로 이 클래스를 상속하지 않는다.
 *
 * ## connectionTimeZone=SERVER (이슈 #359 하위 이슈)
 *  MySQLContainer가 생성하는 JDBC URL은 기본적으로 시간대 파라미터를 포함하지 않는다. 이 경우
 *  MySQL Connector/J는 `connectionTimeZone=LOCAL`(기본값)을 적용해 "서버가 JDBC 클라이언트
 *  JVM과 같은 시간대"라고 가정하는데, 로컬 개발 PC(Asia/Seoul)와 컨테이너(TZ 미설정, UTC)가
 *  다르면 `reservations.created_at`(DEFAULT CURRENT_TIMESTAMP) 같은 DB 서버측 타임스탬프가
 *  9시간 어긋나게 해석된다. `withUrlParam("connectionTimeZone", "SERVER")`로 드라이버가 서버에
 *  실제 세션 시간대를 직접 물어보도록 강제해, 실행 환경(로컬 PC 시간대)과 무관하게 항상 정확한
 *  값을 읽도록 한다. `application.yaml`/`application-loadtest.yaml`의 datasource.url에도 동일한
 *  파라미터를 추가했다 — 둘 중 하나만 고치면 나머지 환경에서 회귀가 재발한다.
 */
@SpringBootTest
abstract class IntegrationTestSupport {
    @Autowired
    protected lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    protected lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @BeforeEach
    fun cleanAll() {
        cleanCircuitBreakers()
        cleanDatabase()
        cleanRedis()
    }

    /** * Resilience4j 서킷 브레이커의 상태를 CLOSED로 강제 전환합니다.
     * 이 메서드는 상태 전환뿐만 아니라 누적된 실패 통계(Metrics)도 함께 초기화합니다.
     */
    private fun cleanCircuitBreakers() {
        // "redis" 외에 다른 서킷이 추가될 수 있으므로 일괄 초기화하는 것이 안전합니다.
        circuitBreakerRegistry.allCircuitBreakers.forEach { circuitBreaker ->
            circuitBreaker.transitionToClosedState()
        }
    }

    /** MySQL 전체 테이블 초기화. FK 제약 때문에 자식 테이블부터 삭제. */
    protected fun cleanDatabase() = transaction {
        TicketsTable.deleteAll() // reservations 참조
        ReservationsTable.deleteAll() // users · events · zones 참조
        AuditLogsTable.deleteAll()
        ZonesTable.deleteAll() // events 참조
        EventsTable.deleteAll()
        UsersTable.deleteAll()
    }

    /**
     * Redis 전체 초기화(FLUSHDB).
     * 개별 패턴 삭제 대신 FLUSHDB 로 처리해 아래 네임스페이스 + Stream/Consumer Group 을 한 번에 제거한다.
     *
     * 비우는 키(Redis 키 명세 v3.1 / RedisKeyFactory 기준):
     *   ZONE:{zoneId}:stock · ZONE:{zoneId}:claimed · ORDER_HOLD:{orderId} · order:owner:{orderId}
     *   webhook:processed:{orderId} · payment:approve:{orderId} · idempotency:order:{userId}:{eventId}
     *   order:pending:{userId}:{eventId} · rate_limit:{ip}:sec|min · queue:order:{eventId}(Stream)
     *   event:info:{eventId} · sse:order:{orderId}(Pub/Sub)
     */
    protected fun cleanRedis() {
        redisTemplate.execute { connection ->
            connection.serverCommands().flushDb()
            null
        }
    }

    companion object {
        private const val REDIS_PORT = 6379

        // ── Singleton containers (공식 패턴) ─────────────────────────────
        // companion 로드 시 1회 start(). @Testcontainers/@Container 미사용. 종료 시 Ryuk 정리.
        private val mysql: MySQLContainer =
            MySQLContainer("mysql:9.7").apply {
                withDatabaseName("snaptix")
                withUsername("snaptix")
                withPassword("snaptix1234")
                // 운영/로드테스트 datasource.url과 동일하게 SERVER 모드 강제 (이슈 #359 하위 이슈).
                withUrlParam("connectionTimeZone", "SERVER")
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
            registry.add("jwt.secret") {
                "integration-test-secret-key-for-snaptix-shared-256bit-minimum"
            }
        }
    }
}
