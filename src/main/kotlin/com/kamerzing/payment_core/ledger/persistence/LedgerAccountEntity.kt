package com.kamerzing.payment_core.ledger.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class LedgerAccountKind { CUSTOMER, SYSTEM }

@Entity
@Table(name = "ledger_accounts")
open class LedgerAccountEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: LedgerAccountKind,

    @Column(nullable = false, length = 3, updatable = false)
    var currency: String = "RUB",

    @Column(name = "balance_minor", nullable = false)
    var balanceMinor: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
