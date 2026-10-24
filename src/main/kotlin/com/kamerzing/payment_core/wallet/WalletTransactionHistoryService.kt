package com.kamerzing.payment_core.wallet

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import com.kamerzing.payment_core.shared.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class WalletTransactionView(
    val paymentId: UUID,
    val type: String,
    val createdAt: Instant,
    val currency: String,
    val deltaMinor: Long,
    val originalPaymentId: UUID?,
)

data class WalletTransactionPage(
    val items: List<WalletTransactionView>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)

@Service
class WalletTransactionHistoryService(private val jdbc: JdbcTemplate) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(principal: PaymentPrincipal, walletId: UUID, page: Int, size: Int): WalletTransactionPage {
        val accountId = jdbc.query(
            "SELECT account_id FROM wallets WHERE id=? AND user_id=?",
            { rs, _ -> rs.getObject("account_id", UUID::class.java) },
            walletId, principal.userId,
        ).singleOrNull() ?: throw ApiException(
            HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource was not found",
        )
        if (page < 0 || size !in 1..100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Page or size is outside the allowed range")
        }

        val totalElements = jdbc.queryForObject(
            """SELECT count(*) FROM ledger_entries e
               JOIN ledger_transactions t ON t.id=e.transaction_id
               JOIN payments p ON p.id=t.payment_id
               WHERE e.account_id=? AND p.status='POSTED'""",
            Long::class.java, accountId,
        ) ?: error("History count is unavailable")
        val items = jdbc.query(
            """SELECT p.id AS payment_id,p.type,p.created_at,p.currency,e.delta_minor,p.original_payment_id
               FROM ledger_entries e
               JOIN ledger_transactions t ON t.id=e.transaction_id
               JOIN payments p ON p.id=t.payment_id
               WHERE e.account_id=? AND p.status='POSTED'
               ORDER BY p.created_at DESC,p.id DESC
               LIMIT ? OFFSET ?""",
            { rs, _ -> WalletTransactionView(
                paymentId = rs.getObject("payment_id", UUID::class.java),
                type = rs.getString("type"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                currency = rs.getString("currency"),
                deltaMinor = rs.getLong("delta_minor"),
                originalPaymentId = rs.getObject("original_payment_id", UUID::class.java),
            ) },
            accountId, size, page.toLong() * size,
        )
        val totalPages = totalElements / size + if (totalElements % size == 0L) 0 else 1
        return WalletTransactionPage(items, page, size, totalElements, totalPages)
    }
}