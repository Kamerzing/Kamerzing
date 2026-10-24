package com.kamerzing.payment_core.ledger

import com.kamerzing.payment_core.ledger.domain.Money
import com.kamerzing.payment_core.shared.web.ApiException
import jakarta.persistence.EntityManager
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

enum class PostingRefusal(val detail: String) {
    INSUFFICIENT_FUNDS("Insufficient funds"),
    BALANCE_LIMIT_EXCEEDED("Balance would exceed the supported range"),
}

/** Shared posting primitive. Callers reserve the key, assess balances, and persist
 * the payment, journal, cached balances, audit, and HTTP result in one transaction.
 * A technical failure in post invalidates that transaction.
 */
@Service
class LedgerPostingService(private val jdbc: JdbcTemplate, private val entityManager: EntityManager) {
    /** Keep the locks until the caller commits a POSTED or REJECTED result. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun assess(sourceAccountId: UUID, targetAccountId: UUID, money: Money): PostingRefusal? =
        when (val plan = plan(sourceAccountId, targetAccountId, money)) {
            is PostingPlan.Ready -> null
            is PostingPlan.Refused -> plan.reason
        }

    @Transactional(propagation = Propagation.MANDATORY)
    fun post(paymentId: UUID): UUID {
        entityManager.flush()
        val payment = jdbc.query(
            "SELECT status, source_account_id, target_account_id, amount_minor, currency FROM payments WHERE id = ? FOR UPDATE",
            { rs, _ -> Posting(rs.getString("status"), rs.getObject("source_account_id", UUID::class.java),
                rs.getObject("target_account_id", UUID::class.java), Money(rs.getLong("amount_minor"), rs.getString("currency"))) },
            paymentId,
        ).singleOrNull() ?: error("Payment must exist in the current transaction")
        check(payment.status == "POSTED") { "Only POSTED payments can be posted" }
        check(payment.source != payment.target) { "Accounts must be distinct" }
        check(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id = ?", Long::class.java, paymentId) == 0L) {
            "Payment was already posted"
        }
        val ready = when (val plan = plan(payment.source, payment.target, payment.money)) {
            is PostingPlan.Ready -> plan
            is PostingPlan.Refused -> throw ApiException(HttpStatus.CONFLICT, plan.reason.name, plan.reason.detail)
        }
        val transactionId = UUID.randomUUID()
        jdbc.update("INSERT INTO ledger_transactions (id, payment_id) VALUES (?, ?)", transactionId, paymentId)
        jdbc.update(
            "INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
            UUID.randomUUID(), transactionId, ready.source.id, -payment.money.amountMinor,
            UUID.randomUUID(), transactionId, ready.target.id, payment.money.amountMinor,
        )
        jdbc.update("UPDATE ledger_accounts SET balance_minor = ? WHERE id = ?", ready.debit, ready.source.id)
        jdbc.update("UPDATE ledger_accounts SET balance_minor = ? WHERE id = ?", ready.credit, ready.target.id)
        return transactionId
    }

    private fun plan(sourceId: UUID, targetId: UUID, money: Money): PostingPlan {
        check(sourceId != targetId) { "Accounts must be distinct" }
        // Stable order prevents opposite transfers from deadlocking. NO KEY UPDATE also
        // coexists with the FK KEY SHARE locks taken while inserting payments.
        val accounts = jdbc.query(
            "SELECT id, kind, currency, balance_minor FROM ledger_accounts WHERE id IN (?, ?) ORDER BY id FOR NO KEY UPDATE",
            { rs, _ -> Account(rs.getObject("id", UUID::class.java), rs.getString("kind"), rs.getString("currency"), rs.getLong("balance_minor")) },
            sourceId, targetId,
        ).associateBy { it.id }
        check(accounts.size == 2) { "Payment accounts must exist" }
        val source = accounts.getValue(sourceId)
        val target = accounts.getValue(targetId)
        check(source.currency == money.currency && target.currency == money.currency)
        val debit: Long
        val credit: Long
        try {
            debit = money.debit(source.balance)
            credit = money.credit(target.balance)
        } catch (_: ArithmeticException) {
            return PostingPlan.Refused(PostingRefusal.BALANCE_LIMIT_EXCEEDED)
        }
        if (source.kind == "CUSTOMER" && debit < 0) {
            return PostingPlan.Refused(PostingRefusal.INSUFFICIENT_FUNDS)
        }
        return PostingPlan.Ready(source, target, debit, credit)
    }

    private sealed interface PostingPlan {
        data class Ready(val source: Account, val target: Account, val debit: Long, val credit: Long) : PostingPlan
        data class Refused(val reason: PostingRefusal) : PostingPlan
    }

    private data class Posting(val status: String, val source: UUID, val target: UUID, val money: Money)
    private data class Account(val id: UUID, val kind: String, val currency: String, val balance: Long)
}
