package com.kamerzing.payment_core.ledger

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import com.kamerzing.payment_core.shared.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class ReconciliationDiscrepancy(
    val code: String,
    val accountId: UUID? = null,
    val ledgerTransactionId: UUID? = null,
    val actualMinor: BigDecimal? = null,
    val expectedMinor: BigDecimal? = null,
)

data class ReconciliationReport(
    val consistent: Boolean,
    val checkedAccounts: Long,
    val checkedLedgerTransactions: Long,
    val discrepancies: List<ReconciliationDiscrepancy>,
)

@Service
class ReconciliationService(private val jdbc: JdbcTemplate) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun reconcile(principal: PaymentPrincipal): ReconciliationReport {
        if (principal.role != "ADMIN") {
            throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "This operation is not permitted")
        }

        val accounts = jdbc.query(
            """SELECT a.id,a.kind,a.balance_minor,COALESCE(SUM(e.delta_minor),0) AS journal_minor
               FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id
               GROUP BY a.id,a.kind,a.balance_minor ORDER BY a.id""",
            { rs, _ -> AccountCheck(
                rs.getObject("id", UUID::class.java),
                rs.getString("kind"),
                BigDecimal.valueOf(rs.getLong("balance_minor")),
                rs.getBigDecimal("journal_minor"),
            ) },
        )
        val transactions = jdbc.query(
            """SELECT t.id,p.status,count(e.id) AS entry_count,COALESCE(SUM(e.delta_minor),0) AS net_minor
               FROM ledger_transactions t JOIN payments p ON p.id=t.payment_id
               LEFT JOIN ledger_entries e ON e.transaction_id=t.id
               GROUP BY t.id,p.status ORDER BY t.id""",
            { rs, _ -> TransactionCheck(
                rs.getObject("id", UUID::class.java),
                rs.getString("status"),
                rs.getLong("entry_count"),
                rs.getBigDecimal("net_minor"),
            ) },
        )
        val discrepancies = mutableListOf<ReconciliationDiscrepancy>()
        for (account in accounts) {
            if (account.balance.compareTo(account.journalBalance) != 0) {
                discrepancies += ReconciliationDiscrepancy(
                    code = "ACCOUNT_BALANCE_MISMATCH",
                    accountId = account.id,
                    actualMinor = account.balance,
                    expectedMinor = account.journalBalance,
                )
            }
            if (account.kind == "CUSTOMER" && account.balance.signum() < 0) {
                discrepancies += ReconciliationDiscrepancy(
                    code = "NEGATIVE_CUSTOMER_BALANCE",
                    accountId = account.id,
                    actualMinor = account.balance,
                    expectedMinor = BigDecimal.ZERO,
                )
            }
        }
        for (transaction in transactions) {
            if (transaction.net.signum() != 0) {
                discrepancies += ReconciliationDiscrepancy(
                    code = "LEDGER_TRANSACTION_UNBALANCED",
                    ledgerTransactionId = transaction.id,
                    actualMinor = transaction.net,
                    expectedMinor = BigDecimal.ZERO,
                )
            }
            if (transaction.status != "POSTED" || transaction.entryCount != 2L) {
                discrepancies += ReconciliationDiscrepancy(
                    code = "LEDGER_TRANSACTION_INCOMPLETE",
                    ledgerTransactionId = transaction.id,
                )
            }
        }
        return ReconciliationReport(
            consistent = discrepancies.isEmpty(),
            checkedAccounts = accounts.size.toLong(),
            checkedLedgerTransactions = transactions.size.toLong(),
            discrepancies = discrepancies,
        )
    }

    private data class AccountCheck(
        val id: UUID,
        val kind: String,
        val balance: BigDecimal,
        val journalBalance: BigDecimal,
    )

    private data class TransactionCheck(
        val id: UUID,
        val status: String,
        val entryCount: Long,
        val net: BigDecimal,
    )
}