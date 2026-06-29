package com.develop.snaptix.global.common.dto

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

class PageRequestDtoTest {
    @Test
    fun `기본 페이징 요청은 유효하다`() {
        val request = PageRequestDto()

        val violations = validator.validate(request)

        assertThat(violations).isEmpty()
        assertThat(request.page).isEqualTo(PageRequestConstraints.DEFAULT_PAGE)
        assertThat(request.size).isEqualTo(PageRequestConstraints.DEFAULT_SIZE)
    }

    @Test
    fun `page는 0 이상이어야 한다`() {
        val violations = validator.validate(PageRequestDto(page = -1))

        assertThat(violations.map { it.propertyPath.toString() })
            .contains("page")
    }

    @Test
    fun `page 최대값은 공통 요청이 아닌 각 API 정책에서 제한한다`() {
        val violations = validator.validate(PageRequestDto(page = 10_001))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `size는 1 이상이어야 한다`() {
        val violations = validator.validate(PageRequestDto(size = 0))

        assertThat(violations.map { it.propertyPath.toString() })
            .contains("size")
    }

    @Test
    fun `size는 최대 페이지 크기 이하이어야 한다`() {
        val violations = validator.validate(PageRequestDto(size = PageRequestConstraints.MAX_SIZE.toInt() + 1))

        assertThat(violations.map { it.propertyPath.toString() })
            .contains("size")
    }

    companion object {
        private val validatorFactory = Validation.buildDefaultValidatorFactory()
        private val validator: Validator = validatorFactory.validator

        @JvmStatic
        @AfterAll
        fun closeValidatorFactory() {
            validatorFactory.close()
        }
    }
}
