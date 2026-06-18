package com.develop.snaptix.global.alert.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class AlertArchitectureTest {
    @Test
    fun `alert package는 Redis 인프라에 의존하지 않는다`() {
        val classes = ClassFileImporter().importPackages("com.develop.snaptix")

        noClasses()
            .that()
            .resideInAPackage("..global.alert..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.data.redis..",
                "com.develop.snaptix.global.redis..",
                "com.develop.snaptix.global.exception.redis..",
            ).check(classes)
    }
}
