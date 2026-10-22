package com.kamerzing.payment_core.payment.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "idempotency_records")
open class IdempotencyRecordEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "actor_id", nullable = false, updatable = false)
    var actorId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 16, updatable = false)
    var commandType: PaymentType,

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    var idempotencyKey: String,

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    var requestFingerprint: String,

    @Column(name = "payment_id")
    var paymentId: UUID? = null,

    @Column(name = "response_status")
    var responseStatus: Short? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    var responseBody: Map<String, Any?>? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
