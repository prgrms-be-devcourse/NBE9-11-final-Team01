package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.config.RedisTtlProperties
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import com.develop.snaptix.global.redis.key.RedisKeyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
class EventCacheRedisGatewayTest {
    @Autowired
    private lateinit var gateway: EventCacheRedisGateway

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var ttl: RedisTtlProperties

    private val keys = RedisKeyFactory()

    @Test
    fun `put 후 get은 동일한 EventInfo를 반환하고 TTL은 1시간 이하`() {
        val eventId = UUID.randomUUID()
        val info = sampleInfo(eventId)

        gateway.put(eventId, info)

        assertThat(gateway.get(eventId)).isEqualTo(info)
        val remaining = redis.getExpire(keys.eventInfo(eventId))
        assertThat(remaining).isGreaterThan(0L).isLessThanOrEqualTo(ttl.eventInfo.seconds)
    }

    @Test
    fun `캐시 미스 시 null`() {
        assertThat(gateway.get(UUID.randomUUID())).isNull()
    }

    @Test
    fun `evict 후 조회하면 null`() {
        val eventId = UUID.randomUUID()
        gateway.put(eventId, sampleInfo(eventId))

        gateway.evict(eventId)

        assertThat(gateway.get(eventId)).isNull()
    }

    @Test
    fun `손상된 JSON은 miss로 간주해 null 반환`() {
        val eventId = UUID.randomUUID()
        redis.opsForValue().set(keys.eventInfo(eventId), "not-a-json{")

        assertThat(gateway.get(eventId)).isNull()
    }

    private fun sampleInfo(eventId: UUID): EventInfo = EventInfo(
        eventId = eventId.toString(),
        name = "콘서트",
        description = "설명",
        location = "서울",
        startTime = "2026-06-23T10:00:00Z",
        endTime = "2026-06-23T12:00:00Z",
        status = "ON_SALE",
        posterUrl = "",
    )

    companion object {
        private const val REDIS_PORT = 6379

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
