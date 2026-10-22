package com.kamerzing.payment_core.ledger.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ledger_entries")
open class LedgerEntryEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "transaction_id", nullable = false, updatable = false)
    var transactionId: UUID,

    @Column(name = "account_id", nullable = false, updatable = false)
    var accountId: UUID,

    @Column(name = "delta_minor", nullable = false, updatable = false)
    var deltaMinor: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
