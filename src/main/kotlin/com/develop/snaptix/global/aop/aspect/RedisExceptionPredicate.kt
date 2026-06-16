package com.develop.snaptix.global.aop.aspect

import org.springframework.dao.DataAccessException
import java.util.function.Predicate

class RedisExceptionPredicate : Predicate<Throwable> {
    override fun test(t: Throwable): Boolean = t is DataAccessException
}
