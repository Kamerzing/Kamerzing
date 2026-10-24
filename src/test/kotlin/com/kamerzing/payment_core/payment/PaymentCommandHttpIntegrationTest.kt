package com.kamerzing.payment_core.payment

import com.kamerzing.payment_core.ledger.LedgerPostingService
import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import com.kamerzing.payment_core.payment.idempotency.IdempotencyService
import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class PaymentCommandHttpIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: JsonMapper,
    private val posting: LedgerPostingService,
    private val idempotency: IdempotencyService,
    private val transactions: TransactionTemplate,
) {
    @LocalServerPort private var port: Int = 0
    private val client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun adminTopUpPostsBalancedJournalAndCurrentRoleIsCheckedOnReplay() {
        val admin = actor(admin = true)
        val receiver = actor()
        val stranger = actor()
        val body = topUpBody(receiver.walletId, 100_000)
        val key = key()
        val path = "/api/v1/admin/top-ups"
        assertProblem(post(path, body, receiver.token, key), 403, "FORBIDDEN")
        assertProblem(post(path, body, key = key), 401, "UNAUTHENTICATED")
        val beforeSystem = accountBalance(SIMULATOR_ACCOUNT_ID)
        val first = post(path, body, admin.token, key)
        assertEquals(201, first.statusCode(), first.body())
        val payment = json(first)
        assertEquals("TOP_UP", payment.path("type").asString())
        assertEquals("POSTED", payment.path("status").asString())
        assertEquals(100_000L, payment.path("amountMinor").asLong())
        assertEquals("RUB", payment.path("currency").asString())
        val paymentId = UUID.fromString(payment.path("id").asString())
        assertEquals("/api/v1/payments/$paymentId", first.headers().firstValue("Location").orElseThrow())
        assertEquals(200, get("/api/v1/payments/$paymentId", admin.token).statusCode())
        assertEquals(200, get("/api/v1/payments/$paymentId", receiver.token).statusCode())
        assertProblem(get("/api/v1/payments/$paymentId", stranger.token), 404, "RESOURCE_NOT_FOUND")
        assertProblem(get("/api/v1/payments/$paymentId"), 401, "UNAUTHENTICATED")
        assertEquals(100_000L, balance(receiver.walletId))
        assertEquals(beforeSystem - 100_000L, accountBalance(SIMULATOR_ACCOUNT_ID))
        assertJournal(paymentId)
        val replay = post(path, """{"currency":"RUB","amountMinor":100000,"walletId":"${receiver.walletId}"}""", admin.token, key)
        assertEquals(201, replay.statusCode(), replay.body())
        assertEquals(first.body(), replay.body())
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE id=?", paymentId))
        assertEquals(100_000L, balance(receiver.walletId))
        jdbc.update("UPDATE users SET role='USER' WHERE id=?", admin.userId)
        assertProblem(post(path, body, admin.token, key), 403, "FORBIDDEN")
        assertEquals(100_000L, balance(receiver.walletId))
    }

    @Test
    fun transferChecksOwnershipButAllowsForeignRecipientAndUsesOneBalancedPosting() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        val stranger = actor()
        assertEquals(201, topUp(admin, sender, 100_000).statusCode())
        val body = transferBody(sender.walletId, receiver.walletId, 25_000)
        val path = "/api/v1/payments/transfers"
        assertProblem(post(path, body, receiver.token, key()), 404, "RESOURCE_NOT_FOUND")
        assertProblem(get("/api/v1/wallets/${receiver.walletId}", sender.token), 404, "RESOURCE_NOT_FOUND")
        val before = count("SELECT count(*) FROM payments WHERE type='TRANSFER' AND actor_id=?", sender.userId)
        val response = post(path, body, sender.token, key())
        assertEquals(201, response.statusCode(), response.body())
        val payment = json(response)
        assertEquals("TRANSFER", payment.path("type").asString())
        assertEquals("POSTED", payment.path("status").asString())
        assertEquals(25_000L, payment.path("amountMinor").asLong())
        assertEquals(75_000L, balance(sender.walletId))
        assertEquals(25_000L, balance(receiver.walletId))
        assertEquals(before + 1, count("SELECT count(*) FROM payments WHERE type='TRANSFER' AND actor_id=?", sender.userId))
        val paymentId = UUID.fromString(payment.path("id").asString())
        assertJournal(paymentId)
        assertEquals(200, get("/api/v1/payments/$paymentId", sender.token).statusCode())
        assertEquals(200, get("/api/v1/payments/$paymentId", receiver.token).statusCode())
        assertProblem(get("/api/v1/payments/$paymentId", stranger.token), 404, "RESOURCE_NOT_FOUND")
        assertProblem(get("/api/v1/wallets/${receiver.walletId}", sender.token), 404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun rejectedSelfTransferLimitAndInsufficientFundsHaveNoJournalAndReplaySameResult() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        val path = "/api/v1/payments/transfers"
        val self = assertProblem(post(path, transferBody(sender.walletId, sender.walletId, 1), sender.token, key()), 409, "SELF_TRANSFER")
        val selfPaymentId = UUID.fromString(self.path("paymentId").asString())
        assertRejected(selfPaymentId)
        assertEquals("SELF_TRANSFER", json(get("/api/v1/payments/$selfPaymentId", sender.token)).path("rejectionCode").asString())
        assertProblem(get("/api/v1/payments/$selfPaymentId", receiver.token), 404, "RESOURCE_NOT_FOUND")
        val limit = assertProblem(post(path, transferBody(sender.walletId, receiver.walletId, 100_000_001), sender.token, key()), 409, "OPERATION_LIMIT_EXCEEDED")
        assertRejected(UUID.fromString(limit.path("paymentId").asString()))
        val body = transferBody(sender.walletId, receiver.walletId, 500)
        val retryKey = key()
        val failed = post(path, body, sender.token, retryKey)
        val failure = assertProblem(failed, 409, "INSUFFICIENT_FUNDS")
        val failurePaymentId = UUID.fromString(failure.path("paymentId").asString())
        assertRejected(failurePaymentId)
        assertProblem(get("/api/v1/payments/$failurePaymentId", receiver.token), 404, "RESOURCE_NOT_FOUND")
        assertEquals(201, topUp(admin, sender, 500).statusCode())
        val replay = post(path, body, sender.token, retryKey)
        assertEquals(409, replay.statusCode(), replay.body())
        assertEquals(failed.body(), replay.body())
        val replayRequestId = replay.headers().firstValue("X-Request-Id").orElseThrow()
        UUID.fromString(replayRequestId)
        assertNotEquals(failure.path("traceId").asString(), replayRequestId)
        assertEquals(500L, balance(sender.walletId))
        assertEquals(0L, balance(receiver.walletId))
        assertEquals(201, post(path, body, sender.token, key()).statusCode())
        assertEquals(0L, balance(sender.walletId))
        assertEquals(500L, balance(receiver.walletId))
    }

    @Test
    fun invalidCommandsAndKeyConflictDoNotCreatePaymentOrOccupyKey() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        val path = "/api/v1/payments/transfers"
        val validBody = transferBody(sender.walletId, receiver.walletId, 100)
        val before = count("SELECT count(*) FROM payments WHERE actor_id=?", sender.userId)
        for (body in listOf(
            transferBody(sender.walletId, receiver.walletId, 0),
            transferBody(sender.walletId, receiver.walletId, -1),
            """{"fromWalletId":"${sender.walletId}","toWalletId":"${receiver.walletId}","amountMinor":100.0,"currency":"RUB"}""",
            """{"fromWalletId":"${sender.walletId}","toWalletId":"${receiver.walletId}","amountMinor":"100","currency":"RUB"}""",
            """{"fromWalletId":"${sender.walletId}","toWalletId":"${receiver.walletId}","amountMinor":100,"currency":"USD"}""",
            """{"fromWalletId":"${sender.walletId}","toWalletId":"${receiver.walletId}","amountMinor":100,"currency":"RUB","role":"ADMIN"}""",
        )) assertProblem(post(path, body, sender.token, key()), 400, "VALIDATION_ERROR")
        assertProblem(post(path, validBody, sender.token), 400, "INVALID_IDEMPOTENCY_KEY")
        assertProblem(post(path, validBody, sender.token, "contains space"), 400, "INVALID_IDEMPOTENCY_KEY")
        assertProblem(post(path, transferBody(sender.walletId, UUID.randomUUID(), 100), sender.token, key()), 404, "RESOURCE_NOT_FOUND")
        assertEquals(before, count("SELECT count(*) FROM payments WHERE actor_id=?", sender.userId))
        assertEquals(201, topUp(admin, sender, 100).statusCode())
        val retryKey = key()
        val first = post(path, validBody, sender.token, retryKey)
        assertEquals(201, first.statusCode(), first.body())
        assertProblem(post(path, transferBody(sender.walletId, receiver.walletId, 101), sender.token, retryKey), 409, "IDEMPOTENCY_CONFLICT")
        assertEquals(0L, balance(sender.walletId))
        assertEquals(100L, balance(receiver.walletId))
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE actor_id=? AND type='TRANSFER'", sender.userId))
    }

    @Test
    fun concurrentSameKeyTransfersOnceAndDifferentKeysCannotOverspend() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        assertEquals(201, topUp(admin, sender, 100_000).statusCode())
        val body = transferBody(sender.walletId, receiver.walletId, 30_000)
        val retryKey = key()
        val same = concurrentPosts(2) { post("/api/v1/payments/transfers", body, sender.token, retryKey) }
        assertEquals(listOf(201, 201), same.map { it.statusCode() }.sorted())
        assertEquals(same[0].body(), same[1].body())
        assertEquals(70_000L, balance(sender.walletId))
        assertEquals(30_000L, balance(receiver.walletId))
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE actor_id=? AND type='TRANSFER'", sender.userId))
        val debits = concurrentPosts(2) {
            post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 50_000), sender.token, key())
        }
        assertEquals(listOf(201, 409), debits.map { it.statusCode() }.sorted())
        val refused = debits.single { it.statusCode() == 409 }
        assertRejected(UUID.fromString(assertProblem(refused, 409, "INSUFFICIENT_FUNDS").path("paymentId").asString()))
        assertEquals(20_000L, balance(sender.walletId))
        assertEquals(80_000L, balance(receiver.walletId))
    }

    @Test
    fun auditFailureRollsBackPaymentJournalBalancesAndKeyThenSameKeyCanRetry() {
        val admin = actor(admin = true)
        val receiver = actor()
        val retryKey = key()
        val body = topUpBody(receiver.walletId, 1_234)
        val beforeSystem = accountBalance(SIMULATOR_ACCOUNT_ID)
        val beforePayments = count("SELECT count(*) FROM payments WHERE actor_id=?", admin.userId)
        val beforeEntries = count("SELECT count(*) FROM ledger_entries")
        val beforeAudit = count("SELECT count(*) FROM audit_events WHERE actor_id=?", admin.userId)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "test_fail_audit_$suffix"
        val trigger = "test_fail_audit_$suffix"
        try {
            // Only this actor is affected; the trigger is test-owned and removed below.
            jdbc.execute("""CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    IF NEW.actor_id = '${admin.userId}'::uuid THEN
                        RAISE EXCEPTION 'injected audit failure';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'}""".trimIndent())
            jdbc.execute("CREATE TRIGGER $trigger BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION $function()")
            val failed = post("/api/v1/admin/top-ups", body, admin.token, retryKey)
            assertProblem(failed, 500, "INTERNAL_ERROR")
            assertFalse(failed.body().contains("injected audit failure"))
            assertEquals(beforePayments, count("SELECT count(*) FROM payments WHERE actor_id=?", admin.userId))
            assertEquals(beforeEntries, count("SELECT count(*) FROM ledger_entries"))
            assertEquals(beforeAudit, count("SELECT count(*) FROM audit_events WHERE actor_id=?", admin.userId))
            assertEquals(0L, count("SELECT count(*) FROM idempotency_records WHERE actor_id=? AND command_type='TOP_UP' AND idempotency_key=?", admin.userId, retryKey))
            assertEquals(0L, balance(receiver.walletId))
            assertEquals(beforeSystem, accountBalance(SIMULATOR_ACCOUNT_ID))
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS $trigger ON audit_events")
            jdbc.execute("DROP FUNCTION IF EXISTS $function()")
        }
        val retried = post("/api/v1/admin/top-ups", body, admin.token, retryKey)
        assertEquals(201, retried.statusCode(), retried.body())
        assertEquals(1_234L, balance(receiver.walletId))
        assertEquals(beforeSystem - 1_234L, accountBalance(SIMULATOR_ACCOUNT_ID))
        assertJournal(UUID.fromString(json(retried).path("id").asString()))
    }

    @Test
    fun refundPostsExactReverseMovementAndKeepsOriginalTransferPosted() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        assertEquals(201, topUp(admin, sender, 2_000).statusCode())
        val transfer = post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 750), sender.token, key())
        assertEquals(201, transfer.statusCode(), transfer.body())
        val originalId = UUID.fromString(json(transfer).path("id").asString())
        val refundKey = key()
        val first = refund(originalId, receiver.token, refundKey)
        assertEquals(201, first.statusCode(), first.body())
        val refundId = UUID.fromString(json(first).path("id").asString())
        assertEquals("REFUND", json(first).path("type").asString())
        assertEquals(originalId.toString(), json(first).path("originalPaymentId").asString())
        assertEquals(750L, json(first).path("amountMinor").asLong())
        assertEquals("POSTED", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String::class.java, originalId))
        assertEquals(2_000L, balance(sender.walletId))
        assertEquals(0L, balance(receiver.walletId))
        assertJournal(refundId)
        assertEquals(-750L, count(
            """SELECT e.delta_minor FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id
               WHERE t.payment_id=? AND e.account_id=(SELECT account_id FROM wallets WHERE id=?)""",
            refundId, receiver.walletId,
        ))
        assertEquals(750L, count(
            """SELECT e.delta_minor FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id
               WHERE t.payment_id=? AND e.account_id=(SELECT account_id FROM wallets WHERE id=?)""",
            refundId, sender.walletId,
        ))
        val replay = refund(originalId, receiver.token, refundKey, "{}")
        assertEquals(201, replay.statusCode(), replay.body())
        assertEquals(first.body(), replay.body())
        val duplicate = assertProblem(refund(originalId, receiver.token, key()), 409, "PAYMENT_ALREADY_REFUNDED")
        assertRejected(UUID.fromString(duplicate.path("paymentId").asString()))
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE original_payment_id=? AND type='REFUND' AND status='POSTED'", originalId))
    }

    @Test
    fun refundChecksRecipientOwnershipBodyAndOriginalTypeBeforeReservingKey() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        val stranger = actor()
        val topUp = topUp(admin, sender, 2_000)
        val topUpId = UUID.fromString(json(topUp).path("id").asString())
        val transfer = post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 500), sender.token, key())
        val originalId = UUID.fromString(json(transfer).path("id").asString())
        val validationKey = key()
        assertProblem(refund(originalId, sender.token, key()), 404, "RESOURCE_NOT_FOUND")
        assertProblem(refund(originalId, stranger.token, key()), 404, "RESOURCE_NOT_FOUND")
        assertProblem(refund(originalId, null, key()), 401, "UNAUTHENTICATED")
        assertProblem(refund(originalId, receiver.token, null), 400, "INVALID_IDEMPOTENCY_KEY")
        for (body in listOf("null", "[]", """{"amountMinor":1}""")) {
            assertProblem(refund(originalId, receiver.token, validationKey, body), 400, "VALIDATION_ERROR")
        }
        assertEquals(0L, count("SELECT count(*) FROM idempotency_records WHERE command_type='REFUND' AND idempotency_key=?", validationKey))
        val wrongType = assertProblem(refund(topUpId, sender.token, key()), 409, "PAYMENT_NOT_REFUNDABLE")
        assertRejected(UUID.fromString(wrongType.path("paymentId").asString()))
        assertEquals(201, refund(originalId, receiver.token, validationKey).statusCode())
    }

    @Test
    fun insufficientRefundReplaysItsRefusalAndNewKeySucceedsAfterFunding() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        val third = actor()
        assertEquals(201, topUp(admin, sender, 1_000).statusCode())
        val transfer = post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 1_000), sender.token, key())
        val originalId = UUID.fromString(json(transfer).path("id").asString())
        assertEquals(201, post("/api/v1/payments/transfers", transferBody(receiver.walletId, third.walletId, 700), receiver.token, key()).statusCode())
        val refusalKey = key()
        val refused = refund(originalId, receiver.token, refusalKey)
        val failure = assertProblem(refused, 409, "INSUFFICIENT_FUNDS")
        assertRejected(UUID.fromString(failure.path("paymentId").asString()))
        assertEquals(0L, balance(sender.walletId))
        assertEquals(300L, balance(receiver.walletId))
        assertEquals(201, topUp(admin, receiver, 700).statusCode())
        val replay = refund(originalId, receiver.token, refusalKey)
        assertEquals(409, replay.statusCode(), replay.body())
        assertEquals(refused.body(), replay.body())
        assertEquals(201, refund(originalId, receiver.token, key()).statusCode())
        assertEquals(1_000L, balance(sender.walletId))
        assertEquals(0L, balance(receiver.walletId))
    }

    @Test
    fun refundOverflowIsPersistedWithoutPostings() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        assertEquals(201, topUp(admin, sender, 1).statusCode())
        val transfer = post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 1), sender.token, key())
        assertEquals(201, transfer.statusCode(), transfer.body())
        val originalId = UUID.fromString(json(transfer).path("id").asString())

        // A valid historical balance at Long.MAX_VALUE is possible when another
        // simulator account funds it. Create that posting through the shared
        // service, bypassing only the current HTTP TOP_UP command limit.
        val systemAccountId = UUID.randomUUID()
        val fixturePaymentId = UUID.randomUUID()
        val senderAccountId = jdbc.queryForObject("SELECT account_id FROM wallets WHERE id=?", UUID::class.java, sender.walletId)!!
        transactions.executeWithoutResult {
            jdbc.update("INSERT INTO ledger_accounts(id,kind,currency,balance_minor) VALUES (?,'SYSTEM','RUB',0)", systemAccountId)
            jdbc.update(
                """INSERT INTO payments(id,type,status,actor_id,source_account_id,target_account_id,amount_minor,currency)
                   VALUES (?,'TOP_UP','POSTED',?,?,?,?,'RUB')""",
                fixturePaymentId, admin.userId, systemAccountId, senderAccountId, Long.MAX_VALUE,
            )
            posting.post(fixturePaymentId)
        }
        assertEquals(Long.MAX_VALUE, balance(sender.walletId))
        val refundKey = key()
        val beforeEntries = count("SELECT count(*) FROM ledger_entries")
        val refused = refund(originalId, receiver.token, refundKey)
        val problem = assertProblem(refused, 409, "BALANCE_LIMIT_EXCEEDED")
        assertRejected(UUID.fromString(problem.path("paymentId").asString()))
        assertEquals(beforeEntries, count("SELECT count(*) FROM ledger_entries"))
        assertEquals(Long.MAX_VALUE, balance(sender.walletId))
        assertEquals(1L, balance(receiver.walletId))
        assertEquals(refused.body(), refund(originalId, receiver.token, refundKey).body())
    }

    @Test
    fun refundUsesOriginalAmountEvenWhenItExceedsCurrentCommandLimit() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        val amount = 100_000_001L
        val systemAccountId = UUID.randomUUID()
        val topUpPaymentId = UUID.randomUUID()
        val originalId = UUID.randomUUID()
        val senderAccountId = jdbc.queryForObject("SELECT account_id FROM wallets WHERE id=?", UUID::class.java, sender.walletId)!!
        val receiverAccountId = jdbc.queryForObject("SELECT account_id FROM wallets WHERE id=?", UUID::class.java, receiver.walletId)!!

        // Build a valid historical transfer through the posting service. New
        // TOP_UP and TRANSFER commands currently reject amounts above the cap.
        transactions.executeWithoutResult {
            jdbc.update("INSERT INTO ledger_accounts(id,kind,currency,balance_minor) VALUES (?,'SYSTEM','RUB',0)", systemAccountId)
            jdbc.update(
                """INSERT INTO payments(id,type,status,actor_id,source_account_id,target_account_id,amount_minor,currency)
                   VALUES (?,'TOP_UP','POSTED',?,?,?,?,'RUB')""",
                topUpPaymentId, admin.userId, systemAccountId, senderAccountId, amount,
            )
            posting.post(topUpPaymentId)
            jdbc.update(
                """INSERT INTO payments(id,type,status,actor_id,source_account_id,target_account_id,amount_minor,currency)
                   VALUES (?,'TRANSFER','POSTED',?,?,?,?,'RUB')""",
                originalId, sender.userId, senderAccountId, receiverAccountId, amount,
            )
            posting.post(originalId)
        }
        val result = refund(originalId, receiver.token, key())
        assertEquals(201, result.statusCode(), result.body())
        assertEquals(amount, json(result).path("amountMinor").asLong())
        assertEquals(amount, balance(sender.walletId))
        assertEquals(0L, balance(receiver.walletId))
        assertJournal(UUID.fromString(json(result).path("id").asString()))
    }

    @Test
    fun concurrentRefundsWithSameAndDifferentKeysHaveOnePostedEffectEach() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        assertEquals(201, topUp(admin, sender, 2_000).statusCode())
        val originalOne = UUID.fromString(json(post(
            "/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 500), sender.token, key(),
        )).path("id").asString())
        val sameKey = key()
        val same = concurrentPosts(2) { refund(originalOne, receiver.token, sameKey) }
        assertEquals(listOf(201, 201), same.map { it.statusCode() }.sorted())
        assertEquals(same[0].body(), same[1].body())
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE original_payment_id=? AND status='POSTED' AND type='REFUND'", originalOne))

        val originalTwo = UUID.fromString(json(post(
            "/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 500), sender.token, key(),
        )).path("id").asString())
        assertProblem(refund(originalTwo, receiver.token, sameKey), 409, "IDEMPOTENCY_CONFLICT")
        assertEquals(0L, count("SELECT count(*) FROM payments WHERE original_payment_id=? AND type='REFUND'", originalTwo))
        val different = concurrentPosts(2) { refund(originalTwo, receiver.token, key()) }
        assertEquals(listOf(201, 409), different.map { it.statusCode() }.sorted())
        assertProblem(different.single { it.statusCode() == 409 }, 409, "PAYMENT_ALREADY_REFUNDED")
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE original_payment_id=? AND status='POSTED' AND type='REFUND'", originalTwo))
        assertEquals(2_000L, balance(sender.walletId))
        assertEquals(0L, balance(receiver.walletId))
    }

    @Test
    fun burstOfOppositeTransfersKeepsAccountsAndJournalConsistent() {
        val admin = actor(admin = true)
        val first = actor()
        val second = actor()
        assertEquals(201, topUp(admin, first, 1_000).statusCode())
        assertEquals(201, topUp(admin, second, 1_000).statusCode())
        val sequence = AtomicInteger()
        val replies = concurrentPosts(24) {
            val forward = sequence.getAndIncrement() % 2 == 0
            val from = if (forward) first else second
            val to = if (forward) second else first
            post("/api/v1/payments/transfers", transferBody(from.walletId, to.walletId, 1), from.token, key())
        }
        assertEquals(24, replies.size)
        assertEquals(24, replies.count { it.statusCode() == 201 }, replies.joinToString { it.body() })
        assertEquals(24, replies.map { json(it).path("id").asString() }.toSet().size)
        assertEquals(1_000L, balance(first.walletId))
        assertEquals(1_000L, balance(second.walletId))
        val report = get("/api/v1/admin/reconciliation", admin.token)
        assertEquals(200, report.statusCode(), report.body())
        assertEquals(true, json(report).path("consistent").asBoolean())
    }

    @Test
    fun changedLimitKeepsSavedSuccessButRejectsNewCommand() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        assertEquals(201, topUp(admin, sender, 200).statusCode())
        val request = TransferRequest(sender.walletId, receiver.walletId, 100, "RUB")
        val previousKey = key()
        val first = post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 100), sender.token, previousKey)
        assertEquals(201, first.statusCode(), first.body())
        val loweredLimit = PaymentCommandService(jdbc, idempotency, posting, 50)
        val principal = PaymentPrincipal(sender.userId, "USER")
        val context = CommandResponseContext("/api/v1/payments/transfers", UUID.randomUUID().toString())
        val replay = transactions.execute { loweredLimit.transfer(principal, previousKey, request, context) }!!
        assertEquals(201, replay.status)
        assertEquals(json(first).path("id").asString(), replay.body["id"])
        val refused = transactions.execute { loweredLimit.transfer(principal, key(), request, context) }!!
        assertEquals(409, refused.status)
        assertEquals("OPERATION_LIMIT_EXCEEDED", refused.body["code"])
        assertRejected(refused.paymentId)
        assertEquals(100L, balance(sender.walletId))
        assertEquals(100L, balance(receiver.walletId))
    }

    @Test
    fun technicalFailureDuringRefundRollsBackKeyPaymentAndJournal() {
        val admin = actor(admin = true)
        val sender = actor()
        val receiver = actor()
        assertEquals(201, topUp(admin, sender, 1_000).statusCode())
        val transfer = post("/api/v1/payments/transfers", transferBody(sender.walletId, receiver.walletId, 600), sender.token, key())
        val originalId = UUID.fromString(json(transfer).path("id").asString())
        val retryKey = key()
        val beforeEntries = count("SELECT count(*) FROM ledger_entries")
        val function = "test_fail_refund_" + UUID.randomUUID().toString().replace("-", "")
        try {
            jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                "IF NEW.actor_id = '" + receiver.userId + "'::uuid AND NEW.event_type = 'REFUND' THEN " +
                "RAISE EXCEPTION 'injected refund audit failure'; END IF; RETURN NEW; END; $$")
            jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION " + function + "()")
            val failed = refund(originalId, receiver.token, retryKey)
            assertProblem(failed, 500, "INTERNAL_ERROR")
            assertFalse(failed.body().contains("injected refund audit failure"))
            assertEquals(beforeEntries, count("SELECT count(*) FROM ledger_entries"))
            assertEquals(0L, count("SELECT count(*) FROM payments WHERE original_payment_id=? AND type='REFUND'", originalId))
            assertEquals(0L, count("SELECT count(*) FROM idempotency_records WHERE actor_id=? AND command_type='REFUND' AND idempotency_key=?", receiver.userId, retryKey))
            assertEquals(400L, balance(sender.walletId))
            assertEquals(600L, balance(receiver.walletId))
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON audit_events")
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()")
        }
        val retried = refund(originalId, receiver.token, retryKey)
        assertEquals(201, retried.statusCode(), retried.body())
        assertJournal(UUID.fromString(json(retried).path("id").asString()))
    }

    private fun refund(originalId: UUID, token: String?, key: String?, body: String? = null): HttpResponse<String> =
        request("POST", "/api/v1/payments/" + originalId + "/refund", body, token, key)

    private fun concurrentPosts(count: Int, action: () -> HttpResponse<String>): List<HttpResponse<String>> {
        val barrier = CyclicBarrier(count)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val calls = List(count) { pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); action() }) }
            return calls.map { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    private data class Actor(val userId: UUID, val walletId: UUID, val token: String)

    private fun actor(admin: Boolean = false): Actor {
        val email = "${UUID.randomUUID()}@example.test"
        val credentials = """{"email":"$email","password":"payment-test-password-123"}"""
        assertEquals(201, post("/api/v1/auth/register", credentials).statusCode())
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", UUID::class.java, email)!!
        if (admin) jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", id)
        val login = post("/api/v1/auth/login", credentials)
        assertEquals(200, login.statusCode(), login.body())
        val token = json(login).path("accessToken").asString()
        val wallet = post("/api/v1/wallets", """{"currency":"RUB"}""", token)
        assertEquals(201, wallet.statusCode(), wallet.body())
        return Actor(id, UUID.fromString(json(wallet).path("id").asString()), token)
    }

    private fun topUp(admin: Actor, recipient: Actor, amountMinor: Long): HttpResponse<String> =
        post("/api/v1/admin/top-ups", topUpBody(recipient.walletId, amountMinor), admin.token, key())

    private fun topUpBody(walletId: UUID, amountMinor: Long): String =
        """{"walletId":"$walletId","amountMinor":$amountMinor,"currency":"RUB"}"""

    private fun transferBody(from: UUID, to: UUID, amountMinor: Long): String =
        """{"fromWalletId":"$from","toWalletId":"$to","amountMinor":$amountMinor,"currency":"RUB"}"""

    private fun key(): String = UUID.randomUUID().toString()

    private fun get(path: String, token: String? = null): HttpResponse<String> = request("GET", path, null, token, null)
    private fun post(path: String, body: String, token: String? = null, key: String? = null): HttpResponse<String> =
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

    private fun assertRejected(paymentId: UUID) {
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String::class.java, paymentId))
        assertEquals(0L, count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?", paymentId))
        assertEquals(1L, count("SELECT count(*) FROM audit_events WHERE payment_id=? AND outcome='REJECTED'", paymentId))
    }

    private fun assertJournal(paymentId: UUID) {
        assertEquals(1L, count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?", paymentId))
        assertEquals(2L, count("SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.payment_id=?", paymentId))
        assertEquals(0, jdbc.queryForObject(
            "SELECT sum(e.delta_minor) FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.payment_id=?",
            BigDecimal::class.java, paymentId,
        )!!.compareTo(BigDecimal.ZERO))
        assertEquals(1L, count("SELECT count(*) FROM audit_events WHERE payment_id=? AND outcome='POSTED'", paymentId))
    }

    private fun balance(walletId: UUID): Long = jdbc.queryForObject(
        "SELECT a.balance_minor FROM wallets w JOIN ledger_accounts a ON a.id=w.account_id WHERE w.id=?",
        Long::class.java, walletId,
    )!!

    private fun accountBalance(accountId: UUID): Long = jdbc.queryForObject(
        "SELECT balance_minor FROM ledger_accounts WHERE id=?", Long::class.java, accountId,
    )!!

    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    private companion object {
        val SIMULATOR_ACCOUNT_ID: UUID = UUID.fromString("a880a12b-e647-483f-9433-98c2c85ff36b")
    }
}
