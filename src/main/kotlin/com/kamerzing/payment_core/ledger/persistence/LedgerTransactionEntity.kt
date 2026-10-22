package com.kamerzing.payment_core.ledger.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ledger_transactions")
open class LedgerTransactionEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "payment_id", nullable = false, updatable = false)
    var paymentId: UUID,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
