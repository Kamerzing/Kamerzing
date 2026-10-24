package com.kamerzing.payment_core.wallet

import com.kamerzing.payment_core.shared.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class WalletView(
    val id: UUID,
    val currency: String,
    val balanceMinor: Long,
    val createdAt: Instant,
)

@Service
class WalletService(private val jdbc: JdbcTemplate) {
    @Transactional
    fun create(userId: UUID, currency: String): WalletView {
        if (currency != "RUB") throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Only RUB is supported")
        // Serialize creation per owner before allocating the account, including across app instances.
        val owner = jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, userId)
        if (owner.isEmpty()) throw notFound()
        if (listMine(userId).isNotEmpty()) {
            throw ApiException(HttpStatus.CONFLICT, "WALLET_ALREADY_EXISTS", "A RUB wallet already exists")
        }
        val accountId = UUID.randomUUID()
        val walletId = UUID.randomUUID()
        jdbc.update("INSERT INTO ledger_accounts (id, kind) VALUES (?, 'CUSTOMER')", accountId)
        jdbc.update("INSERT INTO wallets (id, user_id, account_id) VALUES (?, ?, ?)", walletId, userId, accountId)
        return getMine(userId, walletId)
    }

    @Transactional(readOnly = true)
    fun listMine(userId: UUID): List<WalletView> = query("w.user_id = ?", userId)

    @Transactional(readOnly = true)
    fun getMine(userId: UUID, walletId: UUID): WalletView =
        query("w.user_id = ? AND w.id = ?", userId, walletId).singleOrNull() ?: throw notFound()

    private fun query(predicate: String, vararg args: Any): List<WalletView> = jdbc.query(
        """SELECT w.id, w.currency, a.balance_minor, w.created_at
           FROM wallets w JOIN ledger_accounts a ON a.id = w.account_id WHERE $predicate""",
        { rs, _ -> WalletView(rs.getObject("id", UUID::class.java), rs.getString("currency"),
            rs.getLong("balance_minor"), rs.getTimestamp("created_at").toInstant()) },
        *args,
    )

    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource was not found")
}
