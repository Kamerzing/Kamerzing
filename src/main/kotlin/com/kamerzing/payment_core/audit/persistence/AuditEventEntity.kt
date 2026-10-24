package com.kamerzing.payment_core.audit.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "audit_events")
open class AuditEventEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "actor_id", updatable = false)
    var actorId: UUID? = null,

    @Column(name = "payment_id", updatable = false)
    var paymentId: UUID? = null,

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    var eventType: String,

    @Column(nullable = false, length = 32, updatable = false)
    var outcome: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    var details: Map<String, Any?> = emptyMap(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
