package com.develop.snaptix.domain.payment.service

import com.develop.snaptix.domain.payment.config.MockPaymentWebhookProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class MockPaymentWebhookSignatureVerifier(
    private val properties: MockPaymentWebhookProperties,
) {
    fun isValid(
        rawBody: String,
        signature: String?,
    ): Boolean {
        if (signature.isNullOrBlank()) {
            return false
        }

        val expected = hmacSha256(rawBody)
        val normalized = signature.removePrefix(SIGNATURE_PREFIX)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            normalized.toByteArray(Charsets.UTF_8),
        )
    }

    fun sign(rawBody: String): String = "$SIGNATURE_PREFIX${hmacSha256(rawBody)}"

    private fun hmacSha256(rawBody: String): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(properties.secret.toByteArray(Charsets.UTF_8), HMAC_SHA256))
        return mac
            .doFinal(rawBody.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val HEADER_NAME = "X-Mock-Signature"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val SIGNATURE_PREFIX = "sha256="
    }
}
