package global.aop.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimit(
    val limitPerSecond: Int = 5,
    val limitPerMinute: Int = 20,
)
