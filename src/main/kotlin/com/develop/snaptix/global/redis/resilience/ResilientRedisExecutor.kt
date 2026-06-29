// 위치: src/main/kotlin/com/develop/snaptix/global/redis/resilience/ResilientRedisExecutor.kt
package com.develop.snaptix.global.redis.resilience

// ⚠️ 아래 2개 import는 프로젝트 실제 패키지에 맞게 확인하세요.
import com.develop.snaptix.global.aop.type.RedisAction
import com.develop.snaptix.global.exception.redis.RedisUnavailableException
import com.develop.snaptix.global.redis.support.RedisActionLogger
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/**
 * 모든 게이트웨이 Redis 호출이 통과하는 단일 진입점(choke point).
 *
 * 서킷브레이커 적용 + 실행 타이밍 + 구조화 로깅을 한곳에서 처리한다. AOP `@annotation`이
 * self-invocation·비동기 워커에서 누락되던 문제를 프로그램적 호출로 해소한다.
 *
 * - 성공          → success 로그 + 결과 반환
 * - 서킷 OPEN     → CallNotPermittedException 흡수 → RedisUnavailableException throw
 * - Redis 오류    → failure 로그 후 그대로 재전파 (fail-open/closed 판단은 호출부 책임)
 *
 * 기존 `redis` 서킷 인스턴스를 그대로 사용하므로 AOP 서킷과 상태를 공유한다.
 *
 * 주의: resilience4j `executeSupplier`는 Java 플랫폼 타입을 반환해 Kotlin이 non-null 단정을
 * 삽입한다(null 결과 시 NPE). 그래서 결과를 [Captured] 래퍼로 감싸 통과시키고 밖에서 푼다.
 * (예: `ownerOf`가 키 부재 시 null을 반환하는 경우)
 */
@Component
class ResilientRedisExecutor(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    private val actionLogger: RedisActionLogger,
) {
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("redis")

    fun <T> execute(
        action: RedisAction,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val captured = circuitBreaker.executeSupplier { Captured(block()) }
            actionLogger.success(action, elapsedMs(startedAt), captured.value)
            captured.value
        } catch (e: CallNotPermittedException) {
            actionLogger.circuitOpen(action, e)
            throw RedisUnavailableException()
        } catch (e: DataAccessException) {
            actionLogger.failure(action, elapsedMs(startedAt), e)
            throw e
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

    /** executeSupplier가 null을 거부하므로 결과를 감싸 통과시키는 non-null 홀더. */
    private class Captured<T>(
        val value: T,
    )

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
