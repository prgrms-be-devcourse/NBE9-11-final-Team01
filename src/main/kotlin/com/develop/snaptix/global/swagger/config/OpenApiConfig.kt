package com.develop.snaptix.global.swagger.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val ACCESS_TOKEN_COOKIE = "accessToken"

@Configuration
class OpenApiConfig {
    @Bean
    fun openAPI(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("SnapTix API")
                    .description("SnapTix Ticketing Service API")
                    .version("v1"),
            ).components(
                Components().addSecuritySchemes(
                    ACCESS_TOKEN_COOKIE,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.COOKIE)
                        .name(ACCESS_TOKEN_COOKIE),
                ),
            )
}
