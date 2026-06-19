package com.develop.snaptix.global.security.auth

import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.exception.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class CurrentUserProviderTest {
    private val currentUserProvider = CurrentUserProvider()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `SecurityContext에서 현재 userId를 조회한다`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedUser(userId = 10L, role = UserRole.USER),
                null,
                emptyList(),
            )

        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(10L)
    }

    @Test
    fun `인증 정보가 없으면 BusinessException을 던진다`() {
        assertThrows(BusinessException::class.java) {
            currentUserProvider.getCurrentUserId()
        }
    }
}
