package com.develop.snaptix.global.realtime

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * SSE 모듈의 도메인 무관 경계를 강제한다 (PR-10).
 *
 * `global.realtime`(SSE 코어/계약/구독)은 어떤 도메인(`domain..`)에도 의존하면 안 된다.
 * 의존은 항상 단방향(도메인 어댑터 → realtime). 이게 깨지면 SSE 모듈의 재사용성이 사라진다.
 *
 * NOTE: archunit-junit5 의존이 필요하다(testImplementation).
 *   testImplementation("com.tngtech.archunit:archunit-junit5:<version>")
 */
@AnalyzeClasses(packages = ["com.develop.snaptix"])
class RealtimeArchitectureTest {
    @ArchTest
    val realtimeMustNotDependOnDomain: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..global.realtime..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain..")
}
