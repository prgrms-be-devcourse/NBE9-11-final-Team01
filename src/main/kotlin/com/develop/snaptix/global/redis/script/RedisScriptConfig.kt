package com.develop.snaptix.global.redis.script

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

/**
 * Redis Lua 스크립트 중앙 등록.
 *
 * 모든 호출부(게이트웨이·AOP)는 `DefaultRedisScript`를 직접 만들지 않고 본 빈을 주입받아
 * 사용한다. 이를 통해 원자성 규약을 단일 구현으로 고정한다.
 *
 * 빈이 둘 이상의 동일 타입(RedisScript<Long>)을 가지므로, 주입 시 `@Qualifier`로 구분한다.
 */
@Configuration
class RedisScriptConfig {
    /** 차감+claimed 원자 스크립트. 반환: OK / ALREADY / SOLD_OUT */
    @Bean
    fun decreaseAndClaimScript(): RedisScript<String> =
        DefaultRedisScript(DECREASE_AND_CLAIM_SCRIPT, String::class.java)

    /** 보상(+1 & SREM) 스크립트. 반환: 1 / 0 */
    @Bean
    fun compensateStockScript(): RedisScript<Long> = DefaultRedisScript(COMPENSATE_STOCK_SCRIPT, Long::class.java)

    /** 멱등 키 compare-and-delete 스크립트. 반환: 1 / 0 */
    @Bean
    fun compareAndDeleteScript(): RedisScript<Long> = DefaultRedisScript(COMPARE_AND_DELETE_SCRIPT, Long::class.java)
}
