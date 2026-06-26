package com.develop.snaptix.global.redis.gateway

import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID

@SpringBootTest
class RedisCacheGatewayIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var redisCacheGateway: RedisCacheGateway

    @Autowired
    private lateinit var redis: StringRedisTemplate

    // KeyFactory 주입
    @Autowired
    private lateinit var keyFactory: RedisKeyFactory

    @AfterEach
    fun tearDown() {
        redis.execute { connection ->
            connection.serverCommands().flushDb()
        }
    }

    @Test
    @DisplayName("put - 실제 Redis에 값이 적재되고 TTL이 정확히 설정된다")
    fun put_setsValueAndTtlInRedis() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)
        val expectedJson = """{"id":"$eventId","name":"Test Event"}"""
        val ttl = Duration.ofHours(1) // 명세서 요구사항 1h

        redisCacheGateway.put(key, expectedJson, ttl)

        val actualValue = redis.opsForValue().get(key)
        val expire = redis.getExpire(key)

        assertThat(actualValue).isEqualTo(expectedJson)
        assertThat(expire).isBetween(3500L, 3600L)
    }

    @Test
    @DisplayName("get - 실제 Redis에 적재된 데이터를 정상적으로 읽어온다")
    fun get_retrievesValueFromRedis() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)
        val expectedJson = """{"id":"$eventId","name":"Test Event 2"}"""

        redis.opsForValue().set(key, expectedJson)

        val result = redisCacheGateway.get(key)

        assertThat(result).isEqualTo(expectedJson)
    }

    @Test
    @DisplayName("get - 키가 존재하지 않으면 null을 반환한다")
    fun get_returnsNullWhenKeyDoesNotExist() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)

        val result = redisCacheGateway.get(key)

        assertThat(result).isNull()
    }

    @Test
    @DisplayName("evict - 실제 Redis에서 데이터가 삭제된다")
    fun evict_deletesValueFromRedis() {
        val eventId = UUID.randomUUID()
        val key = keyFactory.eventInfo(eventId)

        redis.opsForValue().set(key, "dummy-data")
        assertThat(redis.hasKey(key)).isTrue()

        redisCacheGateway.evict(key)

        assertThat(redis.hasKey(key)).isFalse()
    }
}
