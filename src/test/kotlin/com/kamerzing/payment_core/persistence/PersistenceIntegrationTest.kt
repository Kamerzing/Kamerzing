package com.kamerzing.payment_core.persistence

import com.kamerzing.payment_core.PaymentCoreApplication
import com.kamerzing.payment_core.audit.persistence.AuditEventEntity
import com.kamerzing.payment_core.identity.persistence.UserEntity
import com.kamerzing.payment_core.identity.persistence.UserRole
import com.kamerzing.payment_core.ledger.persistence.LedgerAccountEntity
import com.kamerzing.payment_core.ledger.persistence.LedgerAccountKind
import com.kamerzing.payment_core.ledger.persistence.LedgerEntryEntity
import com.kamerzing.payment_core.ledger.persistence.LedgerTransactionEntity
import com.kamerzing.payment_core.payment.persistence.IdempotencyRecordEntity
import com.kamerzing.payment_core.payment.persistence.PaymentEntity
import com.kamerzing.payment_core.payment.persistence.PaymentStatus
import com.kamerzing.payment_core.payment.persistence.PaymentType
import com.kamerzing.payment_core.support.PostgresTestConfiguration
import com.kamerzing.payment_core.wallet.persistence.WalletEntity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
@Import(PostgresTestConfiguration::class)
class PersistenceIntegrationTest @Autowired constructor(
    private val postgres: PostgreSQLContainer,
    private val dataSource: DataSource,
) {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    @Transactional
    fun `JPA validates the migrated schema and round trips ledger links enums and JSONB`() {
        val user = UserEntity(email = "${UUID.randomUUID()}@example.test", passwordHash = "test-only-hash", role = UserRole.ADMIN)
        val system = LedgerAccountEntity(kind = LedgerAccountKind.SYSTEM, balanceMinor = -100)
        val customer = LedgerAccountEntity(kind = LedgerAccountKind.CUSTOMER, balanceMinor = 100)
        entityManager.persist(user)
        entityManager.persist(system)
        entityManager.persist(customer)
        entityManager.flush()

        val wallet = WalletEntity(userId = user.id, accountId = customer.id)
        val payment = PaymentEntity(
            type = PaymentType.TOP_UP, status = PaymentStatus.POSTED, actorId = user.id,
            sourceAccountId = system.id, targetAccountId = customer.id, amountMinor = 100,
        )
        entityManager.persist(wallet)
        entityManager.persist(payment)
        entityManager.flush()

        val transaction = LedgerTransactionEntity(paymentId = payment.id)
        entityManager.persist(transaction)
        entityManager.flush()
        val debit = LedgerEntryEntity(transactionId = transaction.id, accountId = system.id, deltaMinor = -100)
        val credit = LedgerEntryEntity(transactionId = transaction.id, accountId = customer.id, deltaMinor = 100)
        val result = IdempotencyRecordEntity(
            actorId = user.id, commandType = PaymentType.TOP_UP, idempotencyKey = UUID.randomUUID().toString(),
            requestFingerprint = "a".repeat(64), paymentId = payment.id, responseStatus = 201,
            responseBody = mapOf("id" to payment.id.toString(), "status" to "POSTED", "amountMinor" to 100L),
        )
        val audit = AuditEventEntity(
            actorId = user.id, paymentId = payment.id, eventType = "TOP_UP_COMPLETED", outcome = "POSTED",
            details = mapOf("source" to "integration-test", "nested" to mapOf("replayed" to false)),
        )
        listOf(debit, credit, result, audit).forEach(entityManager::persist)
        entityManager.flush()
        entityManager.clear()

        assertEquals(UserRole.ADMIN, entityManager.find(UserEntity::class.java, user.id).role)
        assertEquals(customer.id, entityManager.find(WalletEntity::class.java, wallet.id).accountId)
        assertEquals(PaymentType.TOP_UP, entityManager.find(PaymentEntity::class.java, payment.id).type)
        assertEquals(payment.id, entityManager.find(LedgerTransactionEntity::class.java, transaction.id).paymentId)
        assertEquals(-100L, entityManager.find(LedgerAccountEntity::class.java, system.id).balanceMinor)
        assertEquals(100L, entityManager.find(LedgerAccountEntity::class.java, customer.id).balanceMinor)
        assertEquals(0L, entityManager.find(LedgerEntryEntity::class.java, debit.id).deltaMinor +
            entityManager.find(LedgerEntryEntity::class.java, credit.id).deltaMinor)
        val restoredResult = entityManager.find(IdempotencyRecordEntity::class.java, result.id)
        assertEquals(201.toShort(), restoredResult.responseStatus)
        assertEquals(payment.id.toString(), restoredResult.responseBody?.get("id"))
        assertEquals(100L, (restoredResult.responseBody?.get("amountMinor") as Number).toLong())
        val restoredAudit = entityManager.find(AuditEventEntity::class.java, audit.id)
        assertEquals("integration-test", restoredAudit.details["source"])
        assertEquals(mapOf("replayed" to false), restoredAudit.details["nested"])
    }

    @Test
    fun `committed data survives closing and recreating the application context`() {
        val id = UUID.randomUUID()
        try {
            applicationContext().use { context ->
                context.getBean(EntityManagerFactory::class.java).createEntityManager().use { writer ->
                    writer.transaction.begin()
                    writer.persist(UserEntity(id = id, email = "$id@example.test", passwordHash = "test-only-hash"))
                    writer.transaction.commit()
                }
            }
            applicationContext().use { context ->
                context.getBean(EntityManagerFactory::class.java).createEntityManager().use { reader ->
                    val restored = reader.find(UserEntity::class.java, id)
                    assertNotNull(restored)
                    assertEquals("$id@example.test", restored.email)
                }
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM users WHERE id = ?").use { statement ->
                    statement.setObject(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun applicationContext() = SpringApplicationBuilder(RestartConfiguration::class.java)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.config.import=",
            "--spring.main.banner-mode=off",
            "--logging.level.root=WARN",
        )

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackageClasses = [PaymentCoreApplication::class])
    class RestartConfiguration
}
