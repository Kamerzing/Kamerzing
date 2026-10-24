package com.kamerzing.payment_core.payment.idempotency

import com.kamerzing.payment_core.payment.persistence.PaymentType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Fields validated and resolved by the caller before claiming the key. */
data class NormalizedPaymentCommand(
    val sourceWalletId: UUID? = null,
    val targetWalletId: UUID? = null,
    val originalPaymentId: UUID? = null,
    val amountMinor: Long? = null,
    val currency: String? = null,
) {
    fun fingerprint(actorId: UUID, type: PaymentType): String {
        // Fixed field order and explicit nulls make the digest independent of JSON field order.
        val canonical = listOf(
            "payment-core-command-v1", actorId.toString(), type.name,
            sourceWalletId?.toString() ?: "-",
            targetWalletId?.toString() ?: "-",
            originalPaymentId?.toString() ?: "-",
            amountMinor?.toString() ?: "-",
            currency ?: "-",
        ).joinToString("\u0000")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }
}
