package com.develop.snaptix.global.security.jwt

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "jwt")
class JwtProperties {
    lateinit var secret: String
    var accessTokenExpirationSeconds: Long = 600
    var refreshTokenExpirationSeconds: Long = 604_800
    val cookieSecure: Boolean = false
}
