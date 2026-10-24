package com.kamerzing.payment_core.ledger

import com.kamerzing.payment_core.ledger.domain.Money
import com.kamerzing.payment_core.shared.web.ApiException
import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(PostgresTestConfiguration::class)
class LedgerPostingIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val posting: LedgerPostingService,
    manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

    @Test
    fun completeBalancedPostingCommitsAndSystemMayBeNegative() {
        val f = fixture(1000)
        val paymentId = tx { payment(f, f.first, f.second, 250).also { posting.post(it) } }
        assertEquals(750L, balance(f.first))
        assertEquals(250L, balance(f.second))
        assertEquals(-1000L, balance(f.system))
        assertEquals(2L, count("SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.payment_id=?", paymentId))
        assertEquals(0, jdbc.queryForObject(
            "SELECT sum(e.delta_minor) FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.payment_id=?",
            BigDecimal::class.java, paymentId)!!.compareTo(BigDecimal.ZERO))
        assertBalances(f)
    }

    @Test
    fun failureAfterPostingRollsBackPaymentJournalAndBalances() {
        val f = fixture(1000)
        val paymentId = UUID.randomUUID()
        assertFailsWith<IllegalStateException> {
            tx {
                payment(f, f.first, f.second, 100, id = paymentId)
                posting.post(paymentId)
                error("Injected failure before commit")
            }
        }
        assertEquals(0L, count("SELECT count(*) FROM payments WHERE id=?", paymentId))
        assertEquals(0L, count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?", paymentId))
        assertEquals(1000L, balance(f.first))
        assertEquals(0L, balance(f.second))
        assertBalances(f)
    }

    @Test
    fun insufficientFundsRollBackEvenWhenCallerCatchesTheFailure() {
        val f = fixture(100)
        val id = UUID.randomUUID()
        assertFailsWith<UnexpectedRollbackException> {
            tx {
                payment(f, f.first, f.second, 101, id = id)
                val failure = assertFailsWith<ApiException> { posting.post(id) }
                assertEquals("INSUFFICIENT_FUNDS", failure.code)
            }
        }
        assertEquals(0L, count("SELECT count(*) FROM payments WHERE id=?", id))
        assertEquals(100L, balance(f.first))
        assertEquals(0L, balance(f.second))
    }

    @Test
    fun assessedRefusalCanCommitARejectedPaymentWithoutJournal() {
        val f = fixture(100)
        val id = UUID.randomUUID()
        tx {
            assertEquals(PostingRefusal.INSUFFICIENT_FUNDS, posting.assess(f.first, f.second, Money(101)))
            payment(f, f.first, f.second, 101, status = "REJECTED", id = id)
        }
        assertEquals(1L, count("SELECT count(*) FROM payments WHERE id=? AND status='REJECTED'", id))
        assertEquals(0L, count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?", id))
        assertEquals(100L, balance(f.first))
        assertEquals(0L, balance(f.second))
    }

    @Test
    fun serviceRequiresOuterTransactionAndRejectsDuplicateAndRejectedPayments() {
        assertFailsWith<IllegalTransactionStateException> { posting.post(UUID.randomUUID()) }
        val f = fixture(100)
        val rejected = tx { payment(f, f.first, f.second, 200, status = "REJECTED") }
        assertFailsWith<IllegalStateException> { tx { posting.post(rejected) } }
        val posted = tx { payment(f, f.first, f.second, 10).also { posting.post(it) } }
        assertFailsWith<IllegalStateException> { tx { posting.post(posted) } }
        assertEquals(90L, balance(f.first))
        assertEquals(10L, balance(f.second))
        assertEquals(0L, count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?", rejected))
    }

    @Test
    fun overflowOfRecipientAndSystemAccountCausesNoPartialEffect() {
        val f = fixture(Long.MAX_VALUE)
        val freshSystem = tx { account("SYSTEM") }
        val recipientOverflow = assertFailsWith<ApiException> {
            tx { posting.post(payment(f, freshSystem, f.first, 1)) }
        }
        assertEquals("BALANCE_LIMIT_EXCEEDED", recipientOverflow.code)
        assertEquals(0L, balance(freshSystem))
        assertEquals(Long.MAX_VALUE, balance(f.first))
        tx { posting.post(payment(f, f.system, f.second, 1)) }
        assertEquals(Long.MIN_VALUE, balance(f.system))
        val systemOverflow = assertFailsWith<ApiException> {
            tx { posting.post(payment(f, f.system, f.second, 1)) }
        }
        assertEquals("BALANCE_LIMIT_EXCEEDED", systemOverflow.code)
        assertEquals(1L, balance(f.second))
        assertEquals(Long.MIN_VALUE, balance(f.system))
        assertBalances(f)
    }

    @Test
    fun simultaneousDebitsCannotOverspend() {
        val f = fixture(100)
        val barrier = CyclicBarrier(2)
        val results = concurrent {
            try {
                tx {
                    val id = payment(f, f.first, f.second, 70)
                    barrier.await(10, TimeUnit.SECONDS)
                    posting.post(id)
                }
                "POSTED"
            } catch (ex: ApiException) { ex.code }
        }
        assertEquals(listOf("INSUFFICIENT_FUNDS", "POSTED"), results.sorted())
        assertEquals(30L, balance(f.first))
        assertEquals(70L, balance(f.second))
        assertBalances(f)
    }

    @Test
    fun oppositeDirectionsUseStableLocksCompatibleWithForeignKeys() {
        val f = fixture(100)
        tx { posting.post(payment(f, f.system, f.second, 100)) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(f.first to f.second, f.second to f.first).map { (source, target) ->
                pool.submit(Callable {
                    tx {
                        val id = payment(f, source, target, 20)
                        barrier.await(10, TimeUnit.SECONDS)
                        posting.post(id)
                    }
                })
            }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(100L, balance(f.first))
        assertEquals(100L, balance(f.second))
        assertBalances(f)
    }

    @Test
    fun committedJournalPaymentAndAccountIdentityCannotBeRewrittenOrExtended() {
        val f = fixture(100)
        val id = tx { payment(f, f.first, f.second, 10).also { posting.post(it) } }
        val journal = jdbc.queryForObject("SELECT id FROM ledger_transactions WHERE payment_id=?", UUID::class.java, id)!!
        val extra = tx { account("CUSTOMER") }
        val forbidden = listOf(
            "UPDATE payments SET amount_minor=11 WHERE id=?" to id,
            "DELETE FROM payments WHERE id=?" to id,
            "UPDATE ledger_transactions SET created_at=now() WHERE id=?" to journal,
            "DELETE FROM ledger_transactions WHERE id=?" to journal,
            "UPDATE ledger_entries SET delta_minor=42 WHERE transaction_id=?" to journal,
            "DELETE FROM ledger_entries WHERE transaction_id=?" to journal,
            "UPDATE ledger_accounts SET kind='SYSTEM' WHERE id=?" to f.first,
        )
        forbidden.forEach { (sql, key) -> assertFailsWith<DataIntegrityViolationException> { jdbc.update(sql, key) } }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, 1)",
                UUID.randomUUID(), journal, extra)
        }
        assertBalances(f)
    }

    @Test
    fun databaseRejectsIncompleteUnbalancedOrUnaccountedCommit() {
        val f = fixture(100)
        assertFailsWith<RuntimeException> { tx { payment(f, f.first, f.second, 10) } }
        assertFailsWith<RuntimeException> {
            tx {
                val id = payment(f, f.first, f.second, 10)
                val journal = UUID.randomUUID()
                jdbc.update("INSERT INTO ledger_transactions (id, payment_id) VALUES (?, ?)", journal, id)
                jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, -10), (?, ?, ?, 9)",
                    UUID.randomUUID(), journal, f.first, UUID.randomUUID(), journal, f.second)
            }
        }
        assertFailsWith<RuntimeException> {
            tx { jdbc.update("UPDATE ledger_accounts SET balance_minor=99 WHERE id=?", f.first) }
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE ledger_accounts SET balance_minor=-1 WHERE id=?", f.first)
        }
        assertEquals(100L, balance(f.first))
        assertEquals(0L, balance(f.second))
    }

    @Test
    fun rejectedPaymentCannotAcquireEvenACompleteJournal() {
        val f = fixture(100)
        assertFailsWith<RuntimeException> {
            tx {
                val id = payment(f, f.first, f.second, 10, status = "REJECTED")
                val journal = UUID.randomUUID()
                jdbc.update("INSERT INTO ledger_transactions (id, payment_id) VALUES (?, ?)", journal, id)
                jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, -10), (?, ?, ?, 10)",
                    UUID.randomUUID(), journal, f.first, UUID.randomUUID(), journal, f.second)
                jdbc.update("UPDATE ledger_accounts SET balance_minor=90 WHERE id=?", f.first)
                jdbc.update("UPDATE ledger_accounts SET balance_minor=10 WHERE id=?", f.second)
            }
        }
        assertEquals(100L, balance(f.first))
        assertEquals(0L, balance(f.second))
    }

    private fun fixture(funding: Long): Fixture = tx {
        val actor = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id,email,password_hash) VALUES (?, ?, 'test-only')", actor, "$actor@example.test")
        val f = Fixture(actor, account("SYSTEM"), account("CUSTOMER"), account("CUSTOMER"))
        posting.post(payment(f, f.system, f.first, funding))
        f
    }

    private fun account(kind: String): UUID = UUID.randomUUID().also {
        jdbc.update("INSERT INTO ledger_accounts (id,kind) VALUES (?, ?)", it, kind)
    }

    private fun payment(f: Fixture, source: UUID, target: UUID, amount: Long, status: String = "POSTED", id: UUID = UUID.randomUUID()): UUID {
        jdbc.update(
            """INSERT INTO payments (id,type,status,actor_id,source_account_id,target_account_id,amount_minor,rejection_code)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            id, if (source == f.system) "TOP_UP" else "TRANSFER", status, f.actor, source, target, amount,
            if (status == "REJECTED") "INSUFFICIENT_FUNDS" else null,
        )
        return id
    }

    private fun balance(id: UUID): Long = count("SELECT balance_minor FROM ledger_accounts WHERE id=?", id)
    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    private fun assertBalances(f: Fixture) {
        listOf(f.system, f.first, f.second).forEach { id ->
            val sum = jdbc.queryForObject("SELECT COALESCE(sum(delta_minor),0) FROM ledger_entries WHERE account_id=?", BigDecimal::class.java, id)!!
            assertEquals(0, sum.compareTo(BigDecimal.valueOf(balance(id))))
        }
        assertTrue(balance(f.first) >= 0 && balance(f.second) >= 0)
    }

    private fun <T : Any> tx(block: () -> T): T = transactions.execute { block() }!!
    private fun <T> concurrent(block: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val futures = List(2) { pool.submit(Callable { block() }) }
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    private data class Fixture(val actor: UUID, val system: UUID, val first: UUID, val second: UUID)
}
