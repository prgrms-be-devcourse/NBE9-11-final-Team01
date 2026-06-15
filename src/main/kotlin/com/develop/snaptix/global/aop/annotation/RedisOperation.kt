package com.develop.snaptix.global.aop.annotation

import com.develop.snaptix.global.aop.type.RedisAction

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedisOperation(
    val action: RedisAction,
)
