package com.develop.snaptix.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(packages = ["com.develop.snaptix"])
class TestcontainersAccessRules {
    val targetClasses =
        DescribedPredicate.describe<JavaClass>("IntegrationTestSupport가 아닌 클래스") { clazz ->
            !clazz.name.contains("IntegrationTestSupport") && clazz.simpleName != "TestcontainersAccessRules"
        }

    @ArchTest
    val testcontainersOnlyInIntegrationTestSupport =
        noClasses()
            .that(targetClasses)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.testcontainers..")
            .because(
                "Testcontainers(MySQL, Redis)의 생성, 어노테이션 적용 및 라이프사이클 관리 로직은 " +
                    "싱글톤 패턴(컨텍스트 캐싱) 유지를 위해 오직 IntegrationTestSupport에서만 수행되어야 합니다.",
            )
}
