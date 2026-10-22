package com.kamerzing.payment_core.payment.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class PaymentType { TOP_UP, TRANSFER, REFUND }
enum class PaymentStatus { POSTED, REJECTED }

@Entity
@Table(name = "payments")
open class PaymentEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    var type: PaymentType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    var status: PaymentStatus,

    @Column(name = "actor_id", nullable = false, updatable = false)
    var actorId: UUID,

    @Column(name = "source_account_id", nullable = false, updatable = false)
    var sourceAccountId: UUID,

    @Column(name = "target_account_id", nullable = false, updatable = false)
    var targetAccountId: UUID,

    @Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long,

    @Column(nullable = false, length = 3, updatable = false)
    var currency: String = "RUB",

    @Column(name = "rejection_code", length = 64, updatable = false)
    var rejectionCode: String? = null,

    @Column(name = "original_payment_id", updatable = false)
    var originalPaymentId: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
