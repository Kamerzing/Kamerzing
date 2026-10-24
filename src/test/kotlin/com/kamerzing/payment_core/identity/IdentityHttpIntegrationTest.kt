package com.kamerzing.payment_core.identity

import com.kamerzing.payment_core.support.PostgresTestConfiguration
import com.kamerzing.payment_core.shared.web.ApiException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class IdentityHttpIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: JsonMapper,
    private val identities: IdentityService,
) {
    @LocalServerPort private var port: Int = 0
    private val client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun registrationCannotAssignAdminAndNormalizesEmail() {
        val email = "user-${UUID.randomUUID()}@example.test"
        val attempted = credentials(email, "Long-password-123", "ADMIN")
        assertProblem(post("/api/v1/auth/register", attempted), 400, "VALIDATION_ERROR")
        val created = post("/api/v1/auth/register", credentials("  ${email.uppercase()}  ", "Long-password-123"))
        assertEquals(201, created.statusCode(), created.body())
        val user = mapper.readTree(created.body())
        assertEquals(email, user.path("email").asString())
        assertEquals("USER", user.path("role").asString())
        assertFalse(user.has("password"))
        assertFalse(user.has("passwordHash"))
        assertProblem(post("/api/v1/auth/register", credentials(email, "Long-password-123")), 409, "EMAIL_ALREADY_EXISTS")
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM users WHERE email=?", String::class.java, email))
        val login = post("/api/v1/auth/login", credentials(email, "Long-password-123"))
        assertEquals(200, login.statusCode())
        val access = mapper.readTree(login.body()).path("accessToken").asString()
        val me = get("/api/v1/users/me", access)
        assertEquals(200, me.statusCode())
        assertEquals(user.path("id").asString(), mapper.readTree(me.body()).path("id").asString())
        assertProblem(get("/api/v1/admin/reconciliation", access), 403, "FORBIDDEN")
        jdbc.update("UPDATE users SET role='ADMIN' WHERE email=?", email)
        // Role comes from the database on every request, so promotion/revocation takes effect immediately.
        val promotedReport = get("/api/v1/admin/reconciliation", access)
        assertEquals(200, promotedReport.statusCode())
        assertTrue(mapper.readTree(promotedReport.body()).path("consistent").asBoolean())
        jdbc.update("UPDATE users SET role='USER' WHERE email=?", email)
        assertProblem(get("/api/v1/admin/reconciliation", access), 403, "FORBIDDEN")
    }

    @Test
    fun adminBootstrapCreatesNewAccountButNeverPromotesExistingUser() {
        val userEmail = "user-${UUID.randomUUID()}@example.test"
        assertEquals(201, post("/api/v1/auth/register", credentials(userEmail, "Long-password-123")).statusCode())
        val conflict = assertFailsWith<ApiException> {
            identities.bootstrapAdmin(userEmail, "Another-password-123")
        }
        assertEquals("EMAIL_ALREADY_EXISTS", conflict.code)
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM users WHERE email=?", String::class.java, userEmail))
        val adminEmail = "admin-${UUID.randomUUID()}@example.test"
        val admin = identities.bootstrapAdmin(adminEmail, "Another-password-123")
        assertEquals("ADMIN", admin.role)
        val login = post("/api/v1/auth/login", credentials(adminEmail, "Another-password-123"))
        assertEquals(200, login.statusCode())
        val token = mapper.readTree(login.body()).path("accessToken").asString()
        val report = get("/api/v1/admin/reconciliation", token)
        assertEquals(200, report.statusCode())
        assertTrue(mapper.readTree(report.body()).path("consistent").asBoolean())
    }

    @Test
    fun badLoginAndUnauthenticatedRequestsDoNotExposeCredentials() {
        val email = "user-${UUID.randomUUID()}@example.test"
        assertEquals(201, post("/api/v1/auth/register", credentials(email, "Long-password-123")).statusCode())
        for (body in listOf(credentials(email, "Wrong-password-1"), credentials("missing-${UUID.randomUUID()}@example.test", "Long-password-123"))) {
            val reply = post("/api/v1/auth/login", body)
            assertProblem(reply, 401, "UNAUTHENTICATED")
            assertFalse(reply.body().contains("password_hash"))
            assertFalse(reply.body().contains("SELECT"))
        }
        assertProblem(get("/api/v1/users/me"), 401, "UNAUTHENTICATED")
        assertProblem(get("/api/v1/users/me", "tampered.jwt.value"), 401, "UNAUTHENTICATED")
        assertProblem(get("/api/v1/users/me", "Bearer malformed"), 401, "UNAUTHENTICATED")
    }

    @Test
    fun refreshRotatesOnceAndLogoutRevokesAllAccessFromSession() {
        val session = loginNew()
        val first = request("POST", "/api/v1/auth/refresh", refreshBody(session.refresh), "stale-access-token")
        assertEquals(200, first.statusCode(), first.body())
        val secondAccess = mapper.readTree(first.body()).path("accessToken").asString()
        val secondRefresh = mapper.readTree(first.body()).path("refreshToken").asString()
        assertNotEquals(session.refresh, secondRefresh)
        assertProblem(post("/api/v1/auth/refresh", refreshBody(session.refresh)), 401, "UNAUTHENTICATED")
        assertEquals(200, get("/api/v1/users/me", session.access).statusCode())
        assertEquals(200, get("/api/v1/users/me", secondAccess).statusCode())
        assertEquals(204, request("POST", "/api/v1/auth/logout", refreshBody(secondRefresh), "stale-access-token").statusCode())
        assertProblem(get("/api/v1/users/me", session.access), 401, "UNAUTHENTICATED")
        assertProblem(get("/api/v1/users/me", secondAccess), 401, "UNAUTHENTICATED")
        assertProblem(post("/api/v1/auth/refresh", refreshBody(secondRefresh)), 401, "UNAUTHENTICATED")
        assertProblem(post("/api/v1/auth/logout", refreshBody(secondRefresh)), 401, "UNAUTHENTICATED")
        val stored = jdbc.queryForObject("SELECT token_hash FROM refresh_sessions WHERE id=?", String::class.java, session.sessionId)
        assertFalse(stored!!.contains(secondRefresh))
    }

    @Test
    fun expiredSessionInvalidatesAccessAndRefreshRegardlessOfJwtExpiry() {
        val session = loginNew()
        assertEquals(200, get("/api/v1/users/me", session.access).statusCode())
        jdbc.update("UPDATE refresh_sessions SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", session.sessionId)
        assertProblem(get("/api/v1/users/me", session.access), 401, "UNAUTHENTICATED")
        assertProblem(post("/api/v1/auth/refresh", refreshBody(session.refresh)), 401, "UNAUTHENTICATED")
    }

    @Test
    fun signedTokensStillNeedValidClaimsAndActiveSession() {
        val session = loginNew()
        val userId = jdbc.queryForObject("SELECT user_id FROM refresh_sessions WHERE id=?", UUID::class.java, session.sessionId)!!
        val now = Instant.now().epochSecond
        val baseline = mapOf<String, Any>(
            "iss" to "payment-core", "aud" to listOf("payment-core-api"), "sub" to userId.toString(),
            "iat" to now, "exp" to now + 300, "sid" to session.sessionId.toString(), "token_use" to "access",
        )
        assertEquals(200, get("/api/v1/users/me", signed(baseline)).statusCode())
        val invalid = listOf(
            baseline + ("iss" to "another-issuer"),
            baseline + ("aud" to listOf("different-audience")),
            baseline + ("exp" to now - 1),
            baseline + ("token_use" to "refresh"),
            baseline + ("sub" to UUID.randomUUID().toString()),
            baseline + ("sid" to UUID.randomUUID().toString()),
            baseline - "sid",
            baseline - "sub",
        )
        invalid.forEach { claims ->
            assertProblem(get("/api/v1/users/me", signed(claims)), 401, "UNAUTHENTICATED")
        }
        assertProblem(get("/api/v1/users/me", signed(baseline, typ = "refresh+jwt")), 401, "UNAUTHENTICATED")
        val parts = session.access.split(".")
        val damaged = parts.take(2).joinToString(".") + "." + (if (parts[2].first() == 'A') "B" else "A") + parts[2].drop(1)
        assertProblem(get("/api/v1/users/me", damaged), 401, "UNAUTHENTICATED")
        val noneHeader = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        assertProblem(get("/api/v1/users/me", "$noneHeader.${parts[1]}."), 401, "UNAUTHENTICATED")
    }

    private fun signed(claims: Map<String, Any>, typ: String = "JWT"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"$typ"}""".toByteArray())
        val payload = encoder.encodeToString(mapper.writeValueAsString(claims).toByteArray())
        val input = "$header.$payload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(
            "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2UtaW4tcHJvZHVjdGlvbi0xMjM0NTY="
        ), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray()))}"
    }
    @Test
    fun concurrentRefreshCannotIssueTwoSuccessors() {
        val session = loginNew()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                pool.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    post("/api/v1/auth/refresh", refreshBody(session.refresh))
                })
            }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 401), attempts.map { it.statusCode() }.sorted())
            val winner = attempts.single { it.statusCode() == 200 }
            val next = mapper.readTree(winner.body()).path("refreshToken").asString()
            assertEquals(200, post("/api/v1/auth/refresh", refreshBody(next)).statusCode())
        } finally { pool.shutdownNow() }
    }

    private data class Session(val access: String, val refresh: String, val sessionId: UUID)

    private fun loginNew(): Session {
        val email = "user-${UUID.randomUUID()}@example.test"
        val body = credentials(email, "Long-password-123")
        assertEquals(201, post("/api/v1/auth/register", body).statusCode())
        val login = post("/api/v1/auth/login", body)
        assertEquals(200, login.statusCode(), login.body())
        val response = mapper.readTree(login.body())
        val access = response.path("accessToken").asString()
        val refresh = response.path("refreshToken").asString()
        assertTrue(access.isNotBlank() && refresh.isNotBlank())
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email=?", UUID::class.java, email)!!
        val sessionId = jdbc.queryForObject("SELECT id FROM refresh_sessions WHERE user_id=?", UUID::class.java, userId)!!
        return Session(access, refresh, sessionId)
    }

    private fun credentials(email: String, password: String, role: String? = null): String =
        mapper.writeValueAsString(if (role == null) mapOf("email" to email, "password" to password)
            else mapOf("email" to email, "password" to password, "role" to role))

    private fun refreshBody(token: String): String = mapper.writeValueAsString(mapOf("refreshToken" to token))

    private fun get(path: String, token: String? = null): HttpResponse<String> = request("GET", path, null, token)
    private fun post(path: String, body: String): HttpResponse<String> = request("POST", path, body, null)
    private fun request(method: String, path: String, body: String?, token: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"))
        val body = mapper.readTree(response.body())
        assertEquals(code, body.path("code").asString())
        assertEquals(status, body.path("status").asInt())
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), body.path("traceId").asString())
        UUID.fromString(body.path("traceId").asString())
    }
}
