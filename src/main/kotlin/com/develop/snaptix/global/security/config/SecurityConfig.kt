package com.develop.snaptix.global.security.config

import com.develop.snaptix.global.security.handler.CustomAccessDeniedHandler
import com.develop.snaptix.global.security.handler.CustomAuthenticationEntryPoint
import com.develop.snaptix.global.security.jwt.JwtAuthenticationFilter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: ObjectProvider<JwtAuthenticationFilter>,
        customAuthenticationEntryPoint: CustomAuthenticationEntryPoint,
        customAccessDeniedHandler: CustomAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }
            exceptionHandling {
                authenticationEntryPoint = customAuthenticationEntryPoint
                accessDeniedHandler = customAccessDeniedHandler
            }
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.STATELESS
            }
            authorizeHttpRequests {
                authorize("/swagger-ui.html", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/api/v1/auth/signup", permitAll)
                authorize("/api/v1/auth/login", permitAll)
                authorize(HttpMethod.POST, "/api/v1/payments/mock/webhook", permitAll)
                authorize("/actuator/health", permitAll)
                authorize(HttpMethod.GET, "/api/v1/events/**", permitAll)
                authorize("/api/v1/admin/**", hasRole("ADMIN"))
                authorize("/api/v1/staff/**", hasAnyRole("STAFF", "ADMIN"))
                authorize("/actuator/metrics/**", hasRole("ADMIN"))
                authorize(anyRequest, authenticated)
            }
        }

        jwtAuthenticationFilter.ifAvailable {
            http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
        }

        return http.build()
    }
}
