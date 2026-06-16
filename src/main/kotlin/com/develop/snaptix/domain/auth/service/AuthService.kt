package com.develop.snaptix.domain.auth.service

import com.develop.snaptix.domain.auth.dto.LoginRequest
import com.develop.snaptix.domain.auth.dto.LoginResponse
import com.develop.snaptix.domain.auth.dto.SignUpRequest
import com.develop.snaptix.domain.auth.dto.SignUpResponse
import com.develop.snaptix.domain.auth.repository.AuthUserRepository
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.security.jwt.JwtProvider
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val authUserRepository: AuthUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
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

    fun login(request: LoginRequest): LoginResult {
        val user =
            authUserRepository.findByEmail(request.email)
                ?: throw BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS)

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS)
        }

        return LoginResult(
            response = LoginResponse(userId = user.id, role = user.role),
            accessToken = jwtProvider.createAccessToken(userId = user.id, role = user.role),
            refreshToken = jwtProvider.createRefreshToken(userId = user.id, role = user.role),
        )
    }
}
