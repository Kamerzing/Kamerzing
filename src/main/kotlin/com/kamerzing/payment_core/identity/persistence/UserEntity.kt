package com.kamerzing.payment_core.identity.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class UserRole { USER, ADMIN }

@Entity
@Table(name = "users")
open class UserEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 254)
    var email: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var role: UserRole = UserRole.USER,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
