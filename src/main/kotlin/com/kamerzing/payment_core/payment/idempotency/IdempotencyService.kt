package com.kamerzing.payment_core.payment.idempotency

import com.kamerzing.payment_core.payment.persistence.PaymentType
import com.kamerzing.payment_core.shared.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

sealed interface IdempotencyDecision {
    data class Reserved(val recordId: UUID) : IdempotencyDecision
    data class Replay(val paymentId: UUID, val status: Int, val body: Map<String, Any?>) : IdempotencyDecision
}

/** The caller owns one transaction containing reservation, payment, journal, and final response. */
@Service
class IdempotencyService(private val jdbc: JdbcTemplate, private val json: JsonMapper) {
    private val allowedKey = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun validateKey(key: String?): String = key?.takeIf(allowedKey::matches)
        ?: throw ApiException(
            HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
            "Idempotency-Key must contain 1 to 128 letters, digits, periods, underscores, colons, or hyphens",
        )

    /** Must run after current authorization and immutable request validation, before mutable business checks. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun reserveOrReplay(
        actorId: UUID,
        type: PaymentType,
        key: String?,
        command: NormalizedPaymentCommand,
    ): IdempotencyDecision {
        val validKey = validateKey(key)
        val fingerprint = command.fingerprint(actorId, type)
        val recordId = UUID.randomUUID()
        val inserted = jdbc.update(
            """
            INSERT INTO idempotency_records
                (id, actor_id, command_type, idempotency_key, request_fingerprint)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (actor_id, command_type, idempotency_key) DO NOTHING
            """.trimIndent(),
            recordId, actorId, type.name, validKey, fingerprint,
        )
        if (inserted == 1) return IdempotencyDecision.Reserved(recordId)

        // A conflicting INSERT waits for its uncommitted peer. A separate SELECT then sees
        // the committed final result under PostgreSQL READ COMMITTED isolation.
        val existing = jdbc.query(
            """
            SELECT request_fingerprint, payment_id, response_status, response_body::text AS response_body
            FROM idempotency_records
            WHERE actor_id = ? AND command_type = ? AND idempotency_key = ?
            """.trimIndent(),
            { rs, _ -> StoredRecord(
                rs.getString("request_fingerprint"),
                rs.getObject("payment_id", UUID::class.java),
                rs.getInt("response_status").takeUnless { rs.wasNull() },
                rs.getString("response_body"),
            ) },
            actorId, type.name, validKey,
        ).singleOrNull() ?: error("Idempotency reservation disappeared")
        if (existing.fingerprint != fingerprint) {
            throw ApiException(
                HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Idempotency-Key is already associated with a different command",
            )
        }
        return existing.toReplay()
    }

    /** Return the persisted value so first delivery and every replay use the same status and body. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun complete(
        recordId: UUID,
        paymentId: UUID,
        responseStatus: Int,
        responseBody: Map<String, Any?>,
    ): IdempotencyDecision.Replay {
        require(responseStatus == 201 || responseStatus == 409) { "Unsupported persisted payment result" }
        val bodyJson = json.writeValueAsString(responseBody)
        val completed = jdbc.query(
            """
            UPDATE idempotency_records
            SET payment_id = ?, response_status = ?, response_body = ?::jsonb
            WHERE id = ? AND response_status IS NULL
            RETURNING payment_id, response_status, response_body::text AS response_body
            """.trimIndent(),
            { rs, _ -> IdempotencyDecision.Replay(
                rs.getObject("payment_id", UUID::class.java),
                rs.getInt("response_status"),
                parseBody(rs.getString("response_body")),
            ) },
            paymentId, responseStatus, bodyJson, recordId,
        ).singleOrNull() ?: error("Idempotency reservation is already completed or missing")
        return completed
    }

    private fun StoredRecord.toReplay(): IdempotencyDecision.Replay = IdempotencyDecision.Replay(
        paymentId ?: error("Committed idempotency result has no payment"),
        status ?: error("Committed idempotency result has no HTTP status"),
        parseBody(bodyJson ?: error("Committed idempotency result has no body")),
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseBody(value: String): Map<String, Any?> =
        json.readValue(value, Map::class.java) as Map<String, Any?>

    private data class StoredRecord(
        val fingerprint: String,
        val paymentId: UUID?,
        val status: Int?,
        val bodyJson: String?,
    )
}
