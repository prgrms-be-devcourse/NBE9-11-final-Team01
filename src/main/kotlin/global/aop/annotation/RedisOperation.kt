package global.aop.annotation

import global.aop.type.RedisAction

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedisOperation(
    val action: RedisAction,
)
