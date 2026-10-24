package com.kamerzing.payment_core.identity

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import com.kamerzing.payment_core.identity.security.TokenService
import com.kamerzing.payment_core.shared.web.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class UserView(val id: UUID, val email: String, val role: String)
data class TokenResponse(val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long)

@Service
class IdentityService(
    private val jdbc: JdbcTemplate,
    private val passwords: PasswordEncoder,
    private val tokens: TokenService,
    @Value("\${payment.auth.refresh-seconds:2592000}") private val refreshSeconds: Long,
) {
    private val random = SecureRandom()
    private val dummyHash = passwords.encode("constant-timing-dummy-password")

    init { require(refreshSeconds in 3600..2592000) { "Refresh lifetime must be between one hour and 30 days" } }

    @Transactional
    fun register(email: String, password: String): UserView = createUser(email, password, "USER")

    @Transactional
    fun bootstrapAdmin(email: String, password: String): UserView = createUser(email, password, "ADMIN")

    private fun createUser(email: String, password: String, role: String): UserView {
        validatePassword(password)
        val normalized = normalizeEmail(email)
        val user = UserView(UUID.randomUUID(), normalized, role)
        val inserted = jdbc.update(
            "INSERT INTO users(id,email,password_hash,role) VALUES (?,?,?,?) ON CONFLICT(email) DO NOTHING",
            user.id, normalized, passwords.encode(password), role,
        )
        if (inserted != 1) throw ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "An account with this email already exists")
        return user
    }

    @Transactional
    fun login(email: String, password: String): TokenResponse {
        val rows = jdbc.query("SELECT id,password_hash FROM users WHERE email=?", { rs, _ ->
            rs.getObject("id", UUID::class.java) to rs.getString("password_hash")
        }, normalizeEmail(email))
        val row = rows.singleOrNull()
        val validLength = password.toByteArray(StandardCharsets.UTF_8).size in 12..72
        val matches = passwords.matches(if (validLength) password else "invalid-password", row?.second ?: dummyHash)
        if (row == null || !validLength || !matches) unauthenticated()
        val sessionId = UUID.randomUUID()
        val refresh = newRefresh()
        jdbc.update("INSERT INTO refresh_sessions(id,user_id,token_hash,expires_at) VALUES (?,?,?,?)",
            sessionId, row.first, hash(refresh), Timestamp.from(Instant.now().plusSeconds(refreshSeconds)))
        return response(row.first, sessionId, refresh)
    }

    @Transactional
    fun refresh(refreshToken: String): TokenResponse {
        validateRefresh(refreshToken)
        val next = newRefresh()
        // Competing UPDATEs serialize in PostgreSQL and recheck the old hash.
        // Exactly one request can consume the current refresh token.
        val rows = jdbc.query(
            """UPDATE refresh_sessions SET token_hash=?
               WHERE token_hash=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP
               RETURNING id,user_id""",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("user_id", UUID::class.java) },
            hash(next), hash(refreshToken),
        )
        val session = rows.singleOrNull() ?: unauthenticated()
        return response(session.second, session.first, next)
    }

    @Transactional
    fun logout(refreshToken: String) {
        validateRefresh(refreshToken)
        val changed = jdbc.update(
            """UPDATE refresh_sessions SET revoked_at=CURRENT_TIMESTAMP
               WHERE token_hash=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP""",
            hash(refreshToken),
        )
        if (changed != 1) unauthenticated()
    }

    @Transactional(readOnly = true)
    fun authenticated(userId: UUID, sessionId: UUID): PaymentPrincipal? =
        jdbc.query(
            """SELECT u.id,u.role FROM users u JOIN refresh_sessions s ON s.user_id=u.id
               WHERE u.id=? AND s.id=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP""",
            { rs, _ -> PaymentPrincipal(rs.getObject("id", UUID::class.java), rs.getString("role")) },
            userId, sessionId,
        ).singleOrNull()

    @Transactional(readOnly = true)
    fun me(userId: UUID): UserView =
        jdbc.query("SELECT id,email,role FROM users WHERE id=?", { rs, _ ->
            UserView(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("role"))
        }, userId).singleOrNull() ?: unauthenticated()

    private fun response(userId: UUID, sessionId: UUID, refresh: String) =
        TokenResponse(tokens.issue(userId, sessionId), refresh, expiresIn = tokens.accessSeconds)

    private fun newRefresh(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun validateRefresh(value: String) {
        if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) unauthenticated()
    }

    private fun normalizeEmail(email: String): String {
        val value = email.trim().lowercase(Locale.ROOT)
        if (value.length !in 3..254 || !value.matches(Regex("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}"))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A valid email is required")
        }
        return value
    }

    private fun validatePassword(password: String) {
        if (password.toByteArray(StandardCharsets.UTF_8).size !in 12..72 || password.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Password must contain between 12 and 72 UTF-8 bytes")
        }
    }

    private fun unauthenticated(): Nothing =
        throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Valid authentication is required")
}
