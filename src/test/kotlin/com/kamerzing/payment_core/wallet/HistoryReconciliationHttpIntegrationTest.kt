package com.kamerzing.payment_core.wallet

import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class HistoryReconciliationHttpIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: JsonMapper,
) {
    @LocalServerPort private var port: Int = 0
    private val client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun historyShowsOnlyOwnPostedMovementsWithSignedAmountsAndStablePages() {
        val admin = actor(admin = true)
        val sender = actor()
        val recipient = actor()
        val stranger = actor()
        val topUp = post("/api/v1/admin/top-ups", topUpBody(sender.walletId, 1_000), admin.token, key())
        assertEquals(201, topUp.statusCode(), topUp.body())
        val topUpId = UUID.fromString(json(topUp).path("id").asString())
        val transfer = post("/api/v1/payments/transfers", transferBody(sender.walletId, recipient.walletId, 400), sender.token, key())
        assertEquals(201, transfer.statusCode(), transfer.body())
        val transferId = UUID.fromString(json(transfer).path("id").asString())
        val refund = post("/api/v1/payments/$transferId/refund", null, recipient.token, key())
        assertEquals(201, refund.statusCode(), refund.body())
        val refundId = UUID.fromString(json(refund).path("id").asString())
        val rejected = post("/api/v1/payments/transfers", transferBody(sender.walletId, recipient.walletId, 1_001), sender.token, key())
        assertProblem(rejected, 409, "INSUFFICIENT_FUNDS")

        val path = "/api/v1/wallets/${sender.walletId}/transactions"
        val all = get("$path?size=10", sender.token)
        assertEquals(200, all.statusCode(), all.body())
        val rows = json(all).path("items")
        assertEquals(3, rows.size())
        assertEquals(3L, json(all).path("totalElements").asLong())
        assertEquals(1L, json(all).path("totalPages").asLong())
        val byPayment = (0 until rows.size()).associate { index -> rows[index].path("paymentId").asString() to rows[index] }
        assertEquals(1_000L, byPayment.getValue(topUpId.toString()).path("deltaMinor").asLong())
        assertEquals(-400L, byPayment.getValue(transferId.toString()).path("deltaMinor").asLong())
        assertEquals(400L, byPayment.getValue(refundId.toString()).path("deltaMinor").asLong())
        assertEquals("REFUND", byPayment.getValue(refundId.toString()).path("type").asString())
        assertEquals(transferId.toString(), byPayment.getValue(refundId.toString()).path("originalPaymentId").asString())
        assertTrue((0 until rows.size()).all { rows[it].path("currency").asString() == "RUB" })
        val accountId = jdbc.queryForObject("SELECT account_id FROM wallets WHERE id=?", UUID::class.java, sender.walletId)!!
        val expected = jdbc.query(
            """SELECT p.id FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id
               JOIN payments p ON p.id=t.payment_id WHERE e.account_id=? AND p.status='POSTED'
               ORDER BY p.created_at DESC,p.id DESC""",
            { rs, _ -> rs.getObject("id", UUID::class.java).toString() }, accountId,
        )
        assertEquals(expected, (0 until rows.size()).map { rows[it].path("paymentId").asString() })
        val pages = (0..3).map { page -> json(get("$path?page=$page&size=1", sender.token)) }
        assertEquals(expected, pages.take(3).map { it.path("items")[0].path("paymentId").asString() })
        assertEquals(0, pages[3].path("items").size())
        assertTrue(pages.take(3).all { it.path("totalElements").asLong() == 3L && it.path("totalPages").asLong() == 3L })

        val recipientRows = json(get("/api/v1/wallets/${recipient.walletId}/transactions", recipient.token)).path("items")
        assertEquals(2, recipientRows.size())
        val recipientDeltas = (0 until recipientRows.size()).associate { index ->
            recipientRows[index].path("paymentId").asString() to recipientRows[index].path("deltaMinor").asLong()
        }
        assertEquals(400L, recipientDeltas[transferId.toString()])
        assertEquals(-400L, recipientDeltas[refundId.toString()])
        assertProblem(get(path, stranger.token), 404, "RESOURCE_NOT_FOUND")
        assertProblem(get(path), 401, "UNAUTHENTICATED")
        for (query in listOf("?page=-1", "?size=0", "?size=101")) {
            assertProblem(get("$path$query", sender.token), 400, "VALIDATION_ERROR")
        }
    }

    @Test
    fun reconciliationRequiresAdminAndDetectsCorruptTestJournalWithoutRepair() {
        val admin = actor(admin = true)
        val customer = actor()
        val topUp = post("/api/v1/admin/top-ups", topUpBody(customer.walletId, 123), admin.token, key())
        assertEquals(201, topUp.statusCode(), topUp.body())
        val paymentId = UUID.fromString(json(topUp).path("id").asString())
        val path = "/api/v1/admin/reconciliation"
        assertProblem(get(path, customer.token), 403, "FORBIDDEN")
        assertProblem(get(path), 401, "UNAUTHENTICATED")
        val before = get(path, admin.token)
        assertEquals(200, before.statusCode(), before.body())
        assertTrue(json(before).path("consistent").asBoolean())
        assertEquals(0, json(before).path("discrepancies").size())
        assertTrue(json(before).path("checkedAccounts").asLong() >= 2)
        assertTrue(json(before).path("checkedLedgerTransactions").asLong() >= 1)

        val entryId = jdbc.queryForObject(
            """SELECT e.id FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id
               WHERE t.payment_id=? AND e.delta_minor>0""", UUID::class.java, paymentId,
        )!!
        val originalDelta = jdbc.queryForObject("SELECT delta_minor FROM ledger_entries WHERE id=?", Long::class.java, entryId)!!
        // Only the disposable Testcontainers database is altered. Restore the
        // original entry and the production integrity trigger in every path.
        setTestEntryDelta(entryId, originalDelta + 1)
        try {
            val report = get(path, admin.token)
            assertEquals(200, report.statusCode(), report.body())
            val body = json(report)
            assertFalse(body.path("consistent").asBoolean())
            val discrepancies = body.path("discrepancies")
            val codes = (0 until discrepancies.size()).map { discrepancies[it].path("code").asString() }.toSet()
            assertTrue("ACCOUNT_BALANCE_MISMATCH" in codes)
            assertTrue("LEDGER_TRANSACTION_UNBALANCED" in codes)
            assertEquals(originalDelta + 1, jdbc.queryForObject("SELECT delta_minor FROM ledger_entries WHERE id=?", Long::class.java, entryId))
        } finally {
            setTestEntryDelta(entryId, originalDelta)
        }
        assertTrue(json(get(path, admin.token)).path("consistent").asBoolean())
    }

    private fun setTestEntryDelta(entryId: UUID, deltaMinor: Long) {
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_immutable")
        try {
            jdbc.update("UPDATE ledger_entries SET delta_minor=? WHERE id=?", deltaMinor, entryId)
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_immutable")
        }
    }

    private data class Actor(val userId: UUID, val walletId: UUID, val token: String)

    private fun actor(admin: Boolean = false): Actor {
        val email = "${UUID.randomUUID()}@example.test"
        val credentials = """{"email":"$email","password":"payment-test-password-123"}"""
        assertEquals(201, post("/api/v1/auth/register", credentials).statusCode())
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email=?", UUID::class.java, email)!!
        if (admin) jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", userId)
        val login = post("/api/v1/auth/login", credentials)
        assertEquals(200, login.statusCode(), login.body())
        val token = json(login).path("accessToken").asString()
        val wallet = post("/api/v1/wallets", """{"currency":"RUB"}""", token)
        assertEquals(201, wallet.statusCode(), wallet.body())
        return Actor(userId, UUID.fromString(json(wallet).path("id").asString()), token)
    }

    private fun topUpBody(walletId: UUID, amountMinor: Long): String =
        """{"walletId":"$walletId","amountMinor":$amountMinor,"currency":"RUB"}"""

    private fun transferBody(from: UUID, to: UUID, amountMinor: Long): String =
        """{"fromWalletId":"$from","toWalletId":"$to","amountMinor":$amountMinor,"currency":"RUB"}"""

    private fun key(): String = UUID.randomUUID().toString()
    private fun get(path: String, token: String? = null): HttpResponse<String> = request("GET", path, null, token, null)
    private fun post(path: String, body: String?, token: String? = null, key: String? = null): HttpResponse<String> =
        request("POST", path, body, token, key)

    private fun request(method: String, path: String, body: String?, token: String?, key: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(20))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (key != null) builder.header("Idempotency-Key", key)
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String): JsonNode {
        assertEquals(status, response.statusCode(), response.body())
        val problem = json(response)
        assertEquals(code, problem.path("code").asString())
        UUID.fromString(problem.path("traceId").asString())
        return problem
    }
}
