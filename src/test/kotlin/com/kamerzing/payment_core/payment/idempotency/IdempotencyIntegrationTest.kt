package com.kamerzing.payment_core.payment.idempotency

import com.kamerzing.payment_core.payment.persistence.PaymentType
import com.kamerzing.payment_core.shared.web.ApiException
import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SpringBootTest
@Import(PostgresTestConfiguration::class)
class IdempotencyIntegrationTest @Autowired constructor(
    private val service: IdempotencyService,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    @Test
    fun `same key and normalized command replay the persisted status body and payment`() {
        val actor = newActor()
        val account = newAccount()
        val command = command()
        val key = UUID.randomUUID().toString()
        val original = inTransaction {
            val reserved = assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
            val payment = rejectedPayment(actor, PaymentType.TRANSFER, account)
            service.complete(reserved.recordId, payment, 409,
                mapOf("paymentId" to payment.toString(), "code" to "OPERATION_LIMIT_EXCEEDED"))
        }

        val replay = inTransaction {
            assertInstanceOf(IdempotencyDecision.Replay::class.java,
                service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command.copy()))
        }
        assertEquals(original, replay)
        assertEquals(1L, countRecords(actor, key))
        assertEquals(1L, countPayments(actor))
    }

    @Test
    fun `changed parameters with same key conflict without changing prior result`() {
        val actor = newActor()
        val account = newAccount()
        val command = command()
        val key = UUID.randomUUID().toString()
        val original = inTransaction {
            val reserved = assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
            val payment = rejectedPayment(actor, PaymentType.TRANSFER, account)
            service.complete(reserved.recordId, payment, 409, mapOf("paymentId" to payment.toString()))
        }

        val conflict = assertThrows(ApiException::class.java) {
            inTransaction { service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command.copy(amountMinor = 124)) }
        }
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code)
        assertEquals(original, inTransaction {
            assertInstanceOf(IdempotencyDecision.Replay::class.java,
                service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
        })
        assertEquals(1L, countPayments(actor))
    }

    @Test
    fun `same text key has independent actor and command scopes`() {
        val firstActor = newActor()
        val secondActor = newActor()
        val account = newAccount()
        val key = UUID.randomUUID().toString()
        val scope = listOf(
            firstActor to PaymentType.TRANSFER,
            secondActor to PaymentType.TRANSFER,
            firstActor to PaymentType.TOP_UP,
        )
        scope.forEach { (actor, type) ->
            inTransaction {
                val reserved = assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                    service.reserveOrReplay(actor, type, key, command()))
                val payment = rejectedPayment(actor, type, account)
                service.complete(reserved.recordId, payment, 409, mapOf("paymentId" to payment.toString()))
            }
        }
        assertEquals(3L, jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", Long::class.java, key))
    }

    @Test
    fun `invalid key is rejected before any reservation`() {
        val actor = newActor()
        listOf(null, "", "bad key", "a".repeat(129), "текст").forEach { badKey ->
            val error = assertThrows(ApiException::class.java) { service.validateKey(badKey) }
            assertEquals("INVALID_IDEMPOTENCY_KEY", error.code)
        }
        assertEquals("a:B-1_.", service.validateKey("a:B-1_."))
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_records WHERE actor_id = ?", Long::class.java, actor))
    }

    @Test
    fun `technical rollback releases key and never leaves payment or result`() {
        val actor = newActor()
        val account = newAccount()
        val key = UUID.randomUUID().toString()
        val command = command()
        assertThrows(DeliberateFailure::class.java) {
            inTransaction {
                assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                    service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
                rejectedPayment(actor, PaymentType.TRANSFER, account)
                throw DeliberateFailure()
            }
        }
        assertEquals(0L, countRecords(actor, key))
        assertEquals(0L, countPayments(actor))

        inTransaction {
            val reserved = assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
            val payment = rejectedPayment(actor, PaymentType.TRANSFER, account)
            service.complete(reserved.recordId, payment, 409, mapOf("paymentId" to payment.toString()))
        }
        assertEquals(1L, countRecords(actor, key))
        assertEquals(1L, countPayments(actor))
    }

    @Test
    fun `two concurrent reservations wait and yield one persisted result`() {
        val actor = newActor()
        val account = newAccount()
        val command = command()
        val key = UUID.randomUUID().toString()
        val reserved = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit(Callable {
                inTransaction {
                    val decision = assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                        service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
                    reserved.countDown()
                    check(allowCommit.await(10, TimeUnit.SECONDS))
                    val payment = rejectedPayment(actor, PaymentType.TRANSFER, account)
                    service.complete(decision.recordId, payment, 409, mapOf("paymentId" to payment.toString()))
                }
            })
            assertTrue(reserved.await(10, TimeUnit.SECONDS))
            val second = workers.submit(Callable {
                secondStarted.countDown()
                inTransaction {
                    assertInstanceOf(IdempotencyDecision.Replay::class.java,
                        service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command))
                }
            })
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(200, TimeUnit.MILLISECONDS) }
            assertFalse(second.isDone)
            allowCommit.countDown()
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
            assertEquals(1L, countRecords(actor, key))
            assertEquals(1L, countPayments(actor))
        } finally {
            allowCommit.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `database rejects incomplete commits and mutation of finalized results`() {
        val actor = newActor()
        val account = newAccount()
        val key = UUID.randomUUID().toString()
        val incompleteCommit = assertThrows(Exception::class.java) {
            inTransaction { service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command()) }
        }
        assertTrue(generateSequence(incompleteCommit as Throwable?) { it.cause }
            .filterIsInstance<SQLException>().any { it.sqlState == "23514" })
        assertEquals(0L, countRecords(actor, key))

        val finalResult = inTransaction {
            val reservation = assertInstanceOf(IdempotencyDecision.Reserved::class.java,
                service.reserveOrReplay(actor, PaymentType.TRANSFER, key, command()))
            val payment = rejectedPayment(actor, PaymentType.TRANSFER, account)
            service.complete(reservation.recordId, payment, 409, mapOf("paymentId" to payment.toString()))
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            inTransaction {
                jdbc.update("UPDATE idempotency_records SET response_body = '{}'::jsonb WHERE payment_id = ?",
                    finalResult.paymentId)
            }
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            inTransaction { jdbc.update("DELETE FROM idempotency_records WHERE payment_id = ?", finalResult.paymentId) }
        }
        assertEquals(1L, countRecords(actor, key))
    }

    private fun command() = NormalizedPaymentCommand(
        sourceWalletId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        targetWalletId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        amountMinor = 123,
        currency = "RUB",
    )

    private fun newActor(): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, 'test-only-hash', 'ADMIN')",
            id, "$id@example.test",
        )
    }

    private fun newAccount(): UUID = UUID.randomUUID().also { id ->
        jdbc.update("INSERT INTO ledger_accounts (id, kind, balance_minor) VALUES (?, 'CUSTOMER', 0)", id)
    }

    private fun rejectedPayment(actor: UUID, type: PaymentType, account: UUID): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            """
            INSERT INTO payments (id, type, status, actor_id, source_account_id, target_account_id,
                                  amount_minor, rejection_code)
            VALUES (?, ?, 'REJECTED', ?, ?, ?, 123, 'OPERATION_LIMIT_EXCEEDED')
            """.trimIndent(),
            id, type.name, actor, account, account,
        )
    }

    private fun countRecords(actor: UUID, key: String): Long = jdbc.queryForObject(
        "SELECT count(*) FROM idempotency_records WHERE actor_id = ? AND idempotency_key = ?",
        Long::class.java, actor, key,
    ) ?: error("Missing count")

    private fun countPayments(actor: UUID): Long = jdbc.queryForObject(
        "SELECT count(*) FROM payments WHERE actor_id = ?", Long::class.java, actor,
    ) ?: error("Missing count")

    private fun <T : Any> inTransaction(action: () -> T): T = tx.execute { action() }
        ?: error("Transaction returned no result")

    private class DeliberateFailure : RuntimeException()
}
