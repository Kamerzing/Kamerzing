package com.kamerzing.payment_core.identity.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.oauth2.core.*
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Component
class TokenService(
    @Value("\${payment.auth.jwt-secret}") secret: String,
    @Value("\${payment.auth.issuer:payment-core}") private val issuer: String,
    @Value("\${payment.auth.audience:payment-core-api}") private val audience: String,
    @Value("\${payment.auth.access-seconds:900}") val accessSeconds: Long,
) {
    private val encoder: JwtEncoder
    private val decoder: JwtDecoder

    init {
        require(accessSeconds in 60..900) { "Access token lifetime must be between 60 and 900 seconds" }
        val keyBytes = try { Base64.getDecoder().decode(secret) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("JWT_SECRET must be a Base64-encoded random key")
        }
        require(keyBytes.size >= 32) { "JWT_SECRET must contain at least 32 random bytes" }
        val key = SecretKeySpec(keyBytes, "HmacSHA256")
        encoder = NimbusJwtEncoder(ImmutableSecret<com.nimbusds.jose.proc.SecurityContext>(key))
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(issuer),
                OAuth2TokenValidator<Jwt> { jwt ->
                    if (jwt.expiresAt != null && jwt.expiresAt!!.isAfter(Instant.now()) &&
                        jwt.audience?.contains(audience) == true && jwt.getClaimAsString("token_use") == "access" &&
                        jwt.headers["typ"] == "JWT"
                    ) OAuth2TokenValidatorResult.success()
                    else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
                },
            ))
        }
    }

    fun issue(userId: UUID, sessionId: UUID): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder().issuer(issuer).audience(listOf(audience))
            .subject(userId.toString()).issuedAt(now).expiresAt(now.plusSeconds(accessSeconds))
            .claim("sid", sessionId.toString()).claim("token_use", "access").build()
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims,
        )).tokenValue
    }

    fun decode(token: String): Jwt = decoder.decode(token)
}
