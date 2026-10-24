package com.kamerzing.payment_core.payment

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import com.kamerzing.payment_core.ledger.LedgerPostingService
import com.kamerzing.payment_core.ledger.domain.Money
import com.kamerzing.payment_core.payment.idempotency.IdempotencyDecision
import com.kamerzing.payment_core.payment.idempotency.IdempotencyService
import com.kamerzing.payment_core.payment.idempotency.NormalizedPaymentCommand
import com.kamerzing.payment_core.payment.persistence.PaymentStatus
import com.kamerzing.payment_core.payment.persistence.PaymentType
import com.kamerzing.payment_core.shared.web.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class TopUpRequest(val walletId: UUID, val amountMinor: Long, val currency: String)
data class TransferRequest(val fromWalletId: UUID, val toWalletId: UUID, val amountMinor: Long, val currency: String)

/** These values are stored with a refused response so an idempotent replay returns the same body. */
data class CommandResponseContext(val instance: String, val traceId: String)

@Service
class PaymentCommandService(
    private val jdbc: JdbcTemplate,
    private val idempotency: IdempotencyService,
    private val posting: LedgerPostingService,
    @Value("\${payment.max-operation-amount-minor:100000000}") private val maxOperationAmountMinor: Long,
) {
    init { require(maxOperationAmountMinor > 0) { "Maximum operation amount must be positive" } }

    /** A rejected attempt is visible only to its initiator; posted movements are also visible to account owners. */
    @Transactional(readOnly = true)
    fun getPayment(principal: PaymentPrincipal, paymentId: UUID): Map<String, Any?> {
        val payment = jdbc.query(
            """SELECT p.id,p.type,p.status,p.amount_minor,p.currency,p.created_at,p.rejection_code,p.original_payment_id
               FROM payments p WHERE p.id=? AND (p.actor_id=? OR (p.status='POSTED' AND EXISTS (
                   SELECT 1 FROM wallets w WHERE w.user_id=?
                     AND w.account_id IN (p.source_account_id,p.target_account_id))))""",
            { rs, _ -> PaymentResult(
                rs.getObject("id", UUID::class.java), rs.getString("type"), rs.getString("status"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("rejection_code"), rs.getObject("original_payment_id", UUID::class.java),
            ) },
            paymentId, principal.userId, principal.userId,
        ).singleOrNull() ?: notFound()
        return linkedMapOf<String, Any?>(
            "id" to payment.id.toString(), "type" to payment.type, "status" to payment.status,
            "amountMinor" to payment.amountMinor, "currency" to payment.currency,
            "createdAt" to payment.createdAt.toString(),
        ).apply {
            payment.rejectionCode?.let { put("rejectionCode", it) }
            payment.originalPaymentId?.let { put("originalPaymentId", it.toString()) }
        }
    }

    @Transactional
    fun topUp(
        principal: PaymentPrincipal,
        key: String?,
        request: TopUpRequest,
        responseContext: CommandResponseContext,
    ): IdempotencyDecision.Replay {
        if (principal.role != "ADMIN") forbidden()
        validateMoney(request.amountMinor, request.currency)
        val validKey = idempotency.validateKey(key)
        val recipient = wallet(request.walletId)
        val systemAccountId = jdbc.query(
            "SELECT id FROM ledger_accounts WHERE id=? AND kind='SYSTEM' AND currency='RUB'",
            { rs, _ -> rs.getObject("id", UUID::class.java) }, SIMULATOR_ACCOUNT_ID,
        ).singleOrNull() ?: error("Simulator account is missing")
        val command = NormalizedPaymentCommand(
            targetWalletId = request.walletId,
            amountMinor = request.amountMinor,
            currency = request.currency,
        )
        return when (val decision = idempotency.reserveOrReplay(principal.userId, PaymentType.TOP_UP, validKey, command)) {
            is IdempotencyDecision.Replay -> decision
            is IdempotencyDecision.Reserved -> {
                val refusal = if (request.amountMinor > maxOperationAmountMinor) LIMIT_REFUSAL
                    else posting.assess(systemAccountId, recipient.accountId, Money(request.amountMinor))
                        ?.let { Refusal(it.name, it.detail) }
                finish(decision.recordId, principal.userId, PaymentType.TOP_UP,
                    systemAccountId, recipient.accountId, request.amountMinor, refusal, responseContext)
            }
        }
    }

    @Transactional
    fun transfer(
        principal: PaymentPrincipal,
        key: String?,
        request: TransferRequest,
        responseContext: CommandResponseContext,
    ): IdempotencyDecision.Replay {
        validateMoney(request.amountMinor, request.currency)
        val validKey = idempotency.validateKey(key)
        val sender = wallet(request.fromWalletId, principal.userId)
        val recipient = wallet(request.toWalletId)
        val command = NormalizedPaymentCommand(
            sourceWalletId = request.fromWalletId,
            targetWalletId = request.toWalletId,
            amountMinor = request.amountMinor,
            currency = request.currency,
        )
        return when (val decision = idempotency.reserveOrReplay(principal.userId, PaymentType.TRANSFER, validKey, command)) {
            is IdempotencyDecision.Replay -> decision
            is IdempotencyDecision.Reserved -> {
                val refusal = when {
                    sender.accountId == recipient.accountId -> SELF_REFUSAL
                    request.amountMinor > maxOperationAmountMinor -> LIMIT_REFUSAL
                    else -> posting.assess(sender.accountId, recipient.accountId, Money(request.amountMinor))
                        ?.let { Refusal(it.name, it.detail) }
                }
                finish(decision.recordId, principal.userId, PaymentType.TRANSFER,
                    sender.accountId, recipient.accountId, request.amountMinor, refusal, responseContext)
            }
        }
    }

    @Transactional
    fun refund(
        principal: PaymentPrincipal,
        originalPaymentId: UUID,
        key: String?,
        responseContext: CommandResponseContext,
    ): IdempotencyDecision.Replay {
        val validKey = idempotency.validateKey(key)
        // Authorization must precede a replay. A posted payment's recipient may
        // inspect it, while a rejected attempt is visible only to its actor.
        val accessible = jdbc.query(
            """SELECT p.id FROM payments p JOIN wallets w ON w.account_id=p.target_account_id
               WHERE p.id=? AND w.user_id=? AND (p.status='POSTED' OR p.actor_id=?)""",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            originalPaymentId, principal.userId, principal.userId,
        ).singleOrNull()
        if (accessible == null) notFound()

        val command = NormalizedPaymentCommand(originalPaymentId = originalPaymentId)
        return when (val decision = idempotency.reserveOrReplay(principal.userId, PaymentType.REFUND, validKey, command)) {
            is IdempotencyDecision.Replay -> decision
            is IdempotencyDecision.Reserved -> {
                // Serialize different keys for the same original payment before
                // acquiring account locks. The committed peer is then visible.
                val original = jdbc.query(
                    """SELECT type,status,source_account_id,target_account_id,amount_minor,currency
                       FROM payments WHERE id=? FOR NO KEY UPDATE""",
                    { rs, _ -> OriginalPayment(
                        rs.getString("type"), rs.getString("status"),
                        rs.getObject("source_account_id", UUID::class.java),
                        rs.getObject("target_account_id", UUID::class.java),
                        rs.getLong("amount_minor"), rs.getString("currency"),
                    ) },
                    originalPaymentId,
                ).singleOrNull() ?: error("Authorized original payment disappeared")
                val refusal = when {
                    original.type != PaymentType.TRANSFER.name || original.status != PaymentStatus.POSTED.name -> NOT_REFUNDABLE
                    jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM payments WHERE original_payment_id=? AND type='REFUND' AND status='POSTED')",
                        Boolean::class.java, originalPaymentId,
                    ) == true -> ALREADY_REFUNDED
                    else -> posting.assess(original.target, original.source, Money(original.amountMinor, original.currency))
                        ?.let { Refusal(it.name, it.detail) }
                }
                finish(decision.recordId, principal.userId, PaymentType.REFUND,
                    original.target, original.source, original.amountMinor, refusal, responseContext, originalPaymentId)
            }
        }
    }

    private fun finish(
        recordId: UUID,
        actorId: UUID,
        type: PaymentType,
        sourceAccountId: UUID,
        targetAccountId: UUID,
        amountMinor: Long,
        refusal: Refusal?,
        context: CommandResponseContext,
        originalPaymentId: UUID? = null,
    ): IdempotencyDecision.Replay {
        val paymentId = UUID.randomUUID()
        val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val status = if (refusal == null) PaymentStatus.POSTED else PaymentStatus.REJECTED
        jdbc.update(
            """INSERT INTO payments(id,type,status,actor_id,source_account_id,target_account_id,
                amount_minor,currency,rejection_code,original_payment_id,created_at) VALUES (?,?,?,?,?,?,?,'RUB',?,?,?)""",
            paymentId, type.name, status.name, actorId, sourceAccountId, targetAccountId,
            amountMinor, refusal?.code, originalPaymentId, Timestamp.from(createdAt),
        )
        if (refusal == null) posting.post(paymentId)
        jdbc.update(
            "INSERT INTO audit_events(id,actor_id,payment_id,event_type,outcome) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), actorId, paymentId, type.name, status.name,
        )
        val body = if (refusal == null) {
            linkedMapOf<String, Any?>(
                "id" to paymentId.toString(), "type" to type.name, "status" to status.name,
                "amountMinor" to amountMinor, "currency" to "RUB", "createdAt" to createdAt.toString(),
            ).apply { originalPaymentId?.let { put("originalPaymentId", it.toString()) } }
        } else {
            mapOf<String, Any?>(
                "type" to "urn:payment-core:problem:${refusal.code.lowercase(Locale.ROOT).replace('_', '-')}",
                "title" to "Conflict", "status" to 409, "detail" to refusal.detail,
                "instance" to context.instance, "code" to refusal.code,
                "traceId" to context.traceId, "paymentId" to paymentId.toString(),
            )
        }
        return idempotency.complete(recordId, paymentId, if (refusal == null) 201 else 409, body)
    }

    private fun wallet(walletId: UUID, ownerId: UUID? = null): WalletAccount = jdbc.query(
        "SELECT account_id FROM wallets WHERE id=?" + (if (ownerId == null) "" else " AND user_id=?"),
        { rs, _ -> WalletAccount(rs.getObject("account_id", UUID::class.java)) },
        *(if (ownerId == null) arrayOf(walletId) else arrayOf(walletId, ownerId)),
    ).singleOrNull() ?: notFound()

    private fun validateMoney(amountMinor: Long, currency: String) {
        if (amountMinor <= 0 || currency != "RUB") {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A positive RUB amount is required")
        }
    }

    private fun notFound(): Nothing =
        throw ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource was not found")

    private fun forbidden(): Nothing =
        throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "This operation is not permitted")

    private data class WalletAccount(val accountId: UUID)
    private data class PaymentResult(
        val id: UUID, val type: String, val status: String, val amountMinor: Long, val currency: String,
        val createdAt: Instant, val rejectionCode: String?, val originalPaymentId: UUID?,
    )
    private data class OriginalPayment(
        val type: String, val status: String, val source: UUID, val target: UUID,
        val amountMinor: Long, val currency: String,
    )
    private data class Refusal(val code: String, val detail: String)

    private companion object {
        val SIMULATOR_ACCOUNT_ID: UUID = UUID.fromString("a880a12b-e647-483f-9433-98c2c85ff36b")
        val SELF_REFUSAL = Refusal("SELF_TRANSFER", "A wallet cannot transfer to itself")
        val LIMIT_REFUSAL = Refusal("OPERATION_LIMIT_EXCEEDED", "Operation amount exceeds the configured limit")
        val NOT_REFUNDABLE = Refusal("PAYMENT_NOT_REFUNDABLE", "Payment is not a posted transfer")
        val ALREADY_REFUNDED = Refusal("PAYMENT_ALREADY_REFUNDED", "Transfer already has a posted refund")
    }
}
