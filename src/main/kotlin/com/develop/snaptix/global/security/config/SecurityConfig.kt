package com.develop.snaptix.global.security.config

import com.develop.snaptix.global.security.handler.CustomAccessDeniedHandler
import com.develop.snaptix.global.security.handler.CustomAuthenticationEntryPoint
import com.develop.snaptix.global.security.jwt.JwtAuthenticationFilter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: ObjectProvider<JwtAuthenticationFilter>,
        authenticationEntryPoint: CustomAuthenticationEntryPoint,
        accessDeniedHandler: CustomAccessDeniedHandler,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }.sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }.authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/api/v1/auth/signup",
                        "/api/v1/auth/login",
                        "/actuator/health",
                    ).permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/events/**",
                    ).permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/staff/**")
                    .hasAnyRole("STAFF", "ADMIN")
                    .requestMatchers("/actuator/metrics/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated()
            }

        jwtAuthenticationFilter.ifAvailable {
            http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
        }

        return http.build()
    }
}
