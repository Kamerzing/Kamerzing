package com.kamerzing.payment_core.wallet.persistence

import com.kamerzing.payment_core.ledger.persistence.LedgerAccountKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "wallets")
open class WalletEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "account_id", nullable = false, updatable = false)
    var accountId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_kind", nullable = false, length = 16, updatable = false)
    var accountKind: LedgerAccountKind = LedgerAccountKind.CUSTOMER,

    @Column(nullable = false, length = 3, updatable = false)
    var currency: String = "RUB",

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
