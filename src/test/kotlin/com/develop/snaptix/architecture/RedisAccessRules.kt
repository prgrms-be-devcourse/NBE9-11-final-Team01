package com.develop.snaptix.architecture

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(packages = ["com.develop.snaptix"])
class RedisAccessRules {
    @ArchTest
    val redisTemplateOnlyInAllowedPackages =
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "com.develop.snaptix.global.redis..",
                "com.develop.snaptix.global.aop..",
                "com.develop.snaptix.global.realtime..",
            ).and()
            .haveSimpleNameNotEndingWith("Test") // ✅ "Test"로 끝나는 클래스 제외
            .should()
            .dependOnClassesThat()
            .haveSimpleName("StringRedisTemplate")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("RedisTemplate")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("RedisConnectionFactory")
            .because(
                "Redis 직접 접근은 global.redis(게이트웨이), global.aop, global.realtime 패키지 " +
                    "내부에서만 허용됩니다. 테스트 코드를 제외한 비즈니스 로직에서는 " +
                    "Gateway를 주입받아 안전하게 사용하세요.",
            )
}
