package com.kamerzing.payment_core.wallet

import com.kamerzing.payment_core.shared.web.ApiException
import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class WalletIntegrationTest @Autowired constructor(
    private val wallets: WalletService,
    private val jdbc: JdbcTemplate,
    private val mapper: JsonMapper,
    manager: PlatformTransactionManager,
) {
    @LocalServerPort private var port: Int = 0
    private val transactions = TransactionTemplate(manager)
    private val client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun opensZeroBalanceWalletAndOnlyOwnerCanReadItOverHttp() {
        val owner = loginNewUser()
        val stranger = loginNewUser()
        assertEquals(401, request("GET", "/api/v1/wallets/me").statusCode())
        val created = request("POST", "/api/v1/wallets", owner, """{"currency":"RUB"}""")
        assertEquals(201, created.statusCode())
        val body = mapper.readTree(created.body())
        val id = body.path("id").asString()
        assertEquals("RUB", body.path("currency").asString())
        assertEquals(0L, body.path("balanceMinor").asLong())
        assertFalse(body.has("accountId"))
        assertEquals("/api/v1/wallets/$id", created.headers().firstValue("Location").orElseThrow())
        assertEquals(200, request("GET", "/api/v1/wallets/$id", owner).statusCode())
        val mine = mapper.readTree(request("GET", "/api/v1/wallets/me", owner).body())
        assertEquals(1, mine.size())
        assertEquals(id, mine[0].path("id").asString())
        assertProblem(request("GET", "/api/v1/wallets/$id", stranger), 404, "RESOURCE_NOT_FOUND")
        assertProblem(request("GET", "/api/v1/wallets/${UUID.randomUUID()}", stranger), 404, "RESOURCE_NOT_FOUND")
        assertEquals(0, mapper.readTree(request("GET", "/api/v1/wallets/me", stranger).body()).size())
        assertProblem(request("POST", "/api/v1/wallets", owner, """{"currency":"RUB"}"""), 409, "WALLET_ALREADY_EXISTS")
        val admin = loginNewUser(admin = true)
        assertProblem(request("GET", "/api/v1/wallets/$id", admin), 404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun invalidWalletCommandsCannotAllocateAccounts() {
        val token = loginNewUser()
        val before = count("SELECT count(*) FROM ledger_accounts")
        listOf("""{"currency":"USD"}""", """{"currency":"rub"}""", """{}""",
            """{"currency":"RUB","userId":"${UUID.randomUUID()}"}""").forEach { body ->
            assertProblem(request("POST", "/api/v1/wallets", token, body), 400, "VALIDATION_ERROR")
        }
        assertEquals(before, count("SELECT count(*) FROM ledger_accounts"))
    }

    @Test
    fun concurrentCreationAllocatesExactlyOneAccountAndWallet() {
        val owner = user()
        val before = count("SELECT count(*) FROM ledger_accounts")
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                pool.submit(Callable {
                    try {
                        transactions.execute {
                            jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)
                            barrier.await(10, TimeUnit.SECONDS)
                            wallets.create(owner, "RUB")
                        }
                        "CREATED"
                    } catch (ex: ApiException) { ex.code }
                })
            }
            assertEquals(listOf("CREATED", "WALLET_ALREADY_EXISTS"), futures.map { it.get(20, TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow() }
        assertEquals(before + 1, count("SELECT count(*) FROM ledger_accounts"))
        assertEquals(1L, count("SELECT count(*) FROM wallets WHERE user_id=?", owner))
        assertEquals(0L, wallets.listMine(owner).single().balanceMinor)
    }

    @Test
    fun walletInsertFailureRollsBackPreviouslyInsertedAccount() {
        val owner = user()
        val before = count("SELECT count(*) FROM ledger_accounts")
        // Transactional DDL injects failure specifically at wallet INSERT, after account INSERT.
        // Rolling back removes both the test constraint and the account.
        assertFailsWith<DataIntegrityViolationException> {
            transactions.execute {
                jdbc.execute("ALTER TABLE wallets ADD CONSTRAINT test_wallet_insert_failure CHECK (user_id <> '$owner'::uuid)")
                wallets.create(owner, "RUB")
            }
        }
        assertEquals(before, count("SELECT count(*) FROM ledger_accounts"))
        assertTrue(wallets.listMine(owner).isEmpty())
        assertEquals(0L, wallets.create(owner, "RUB").balanceMinor)
    }

    @Test
    fun walletOwnerAccountAndCurrencyCannotBeReassignedThroughSql() {
        val owner = user()
        val other = user()
        val wallet = wallets.create(owner, "RUB")
        val otherWallet = wallets.create(other, "RUB")
        val account = jdbc.queryForObject("SELECT account_id FROM wallets WHERE id=?", UUID::class.java, otherWallet.id)!!
        assertFailsWith<DataIntegrityViolationException> { jdbc.update("UPDATE wallets SET user_id=? WHERE id=?", other, wallet.id) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update("UPDATE wallets SET account_id=? WHERE id=?", account, wallet.id) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update("UPDATE wallets SET currency='USD' WHERE id=?", wallet.id) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update("DELETE FROM wallets WHERE id=?", wallet.id) }
        assertEquals(wallet.id, wallets.getMine(owner, wallet.id).id)
        assertEquals("RESOURCE_NOT_FOUND", assertFailsWith<ApiException> { wallets.getMine(other, wallet.id) }.code)
    }

    private fun user(): UUID = UUID.randomUUID().also {
        jdbc.update("INSERT INTO users (id,email,password_hash) VALUES (?,?,'test-only')", it, "$it@example.test")
    }

    private fun loginNewUser(admin: Boolean = false): String {
        val email = "${UUID.randomUUID()}@example.test"
        val credentials = """{"email":"$email","password":"wallet-test-password-123"}"""
        assertEquals(201, request("POST", "/api/v1/auth/register", body = credentials).statusCode())
        if (admin) jdbc.update("UPDATE users SET role='ADMIN' WHERE email=?", email)
        val login = request("POST", "/api/v1/auth/login", body = credentials)
        assertEquals(200, login.statusCode())
        return mapper.readTree(login.body()).path("accessToken").asString()
    }

    private fun request(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(15))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        val body = mapper.readTree(response.body())
        assertEquals(code, body.path("code").asString())
        UUID.fromString(body.path("traceId").asString())
    }

    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!
}
