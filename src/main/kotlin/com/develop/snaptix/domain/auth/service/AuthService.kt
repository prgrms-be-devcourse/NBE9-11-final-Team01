package com.develop.snaptix.domain.auth.service

import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.dto.SignUpResponse
import com.develop.snaptix.domain.auth.repository.AuthUserRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val authUserRepository: AuthUserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun signUp(request: SignUpRequest): SignUpResponse {
        if (authUserRepository.existsByEmail(request.email)) {
            throw BusinessException(ErrorCode.DUPLICATE_EMAIL)
        }

        val encodedPassword =
            requireNotNull(passwordEncoder.encode(request.password)) {
                "PasswordEncoder returned null"
            }

        val userId =
            authUserRepository.saveUser(
                email = request.email,
                encodedPassword = encodedPassword,
            )

        return SignUpResponse(
            userId = userId,
            email = request.email,
        )
    }
}
