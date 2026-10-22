package com.kamerzing.payment_core.persistence

import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
@Import(PostgresTestConfiguration::class)
class DatabaseSchemaIntegrationTest @Autowired constructor(
    private val dataSource: DataSource,
    private val postgres: PostgreSQLContainer,
) {
    @Test
    fun `migrations create all tables in an empty schema and repeat without changes`() {
        val schema = "migration_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            val flyway = Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()

            val first = flyway.migrate()
            assertEquals(1, first.migrationsExecuted)
            assertEquals(0, flyway.migrate().migrationsExecuted)
            assertTrue(flyway.validateWithResult().validationSuccessful)
            assertEquals("1", flyway.info().current()?.version?.version)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name IN (
                        'users', 'ledger_accounts', 'wallets', 'payments',
                        'ledger_transactions', 'ledger_entries', 'idempotency_records', 'audit_events'
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { result ->
                        result.next()
                        assertEquals(8, result.getInt(1))
                    }
                }
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }

    @Test
    fun `only technical accounts permit negative balances and all accounts use RUB`() = transaction { connection ->
        account(connection, kind = "SYSTEM", balance = -100_000)
        account(connection, balance = 0)
        rejects(connection, "23514") { account(connection, balance = -1) }
        rejects(connection, "23514") { account(connection, currency = "USD") }
    }

    @Test
    fun `wallet has a single owner currency and a customer account`() = transaction { connection ->
        val user = user(connection)
        val firstAccount = account(connection)
        wallet(connection, user, firstAccount)
        rejects(connection, "23505") { wallet(connection, user, account(connection)) }
        rejects(connection, "23505") { wallet(connection, user(connection), firstAccount) }
        rejects(connection, "23503") { wallet(connection, user(connection), account(connection, kind = "SYSTEM")) }
        rejects(connection, "23503") { wallet(connection, UUID.randomUUID(), account(connection)) }
    }

    @Test
    fun `user email is normalized unique and role is constrained`() = transaction { connection ->
        val email = "${UUID.randomUUID()}@example.test"
        user(connection, email)
        rejects(connection, "23505") { user(connection, email) }
        rejects(connection, "23514") { user(connection, "UpperCase@example.test") }
        rejects(connection, "23514") {
            execute(connection, "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), "${UUID.randomUUID()}@example.test", "test-only-hash", "OWNER")
        }
    }

    @Test
    fun `payments require positive amounts RUB existing actors and accounts`() = transaction { connection ->
        val actor = user(connection)
        val source = account(connection)
        val target = account(connection)
        rejects(connection, "23514") { payment(connection, actor, source, target, amount = 0) }
        rejects(connection, "23514") { payment(connection, actor, source, target, amount = -1) }
        rejects(connection, "23514") { payment(connection, actor, source, target, currency = "USD") }
        rejects(connection, "23503") { payment(connection, UUID.randomUUID(), source, target) }
        rejects(connection, "23503") { payment(connection, actor, source, UUID.randomUUID()) }
    }

    @Test
    fun `rejected self transfer is representable but posted self transfer is rejected`() = transaction { connection ->
        val actor = user(connection)
        val source = account(connection)
        payment(connection, actor, source, source, status = "REJECTED", rejectionCode = "SELF_TRANSFER")
        rejects(connection, "23514") { payment(connection, actor, source, source) }
        rejects(connection, "23514") {
            payment(connection, actor, source, account(connection), status = "REJECTED", rejectionCode = null)
        }
        rejects(connection, "23514") {
            payment(connection, actor, source, account(connection), rejectionCode = "INSUFFICIENT_FUNDS")
        }
    }

    @Test
    fun `refund uniqueness covers successful refunds while allowing rejected attempts`() = transaction { connection ->
        val actor = user(connection)
        val source = account(connection)
        val target = account(connection)
        val original = payment(connection, actor, source, target)
        payment(connection, actor, target, source, type = "REFUND", status = "REJECTED", original = original)
        payment(connection, actor, target, source, type = "REFUND", original = original)
        rejects(connection, "23505") {
            payment(connection, actor, target, source, type = "REFUND", original = original)
        }
        payment(connection, actor, target, source, type = "REFUND", status = "REJECTED", original = original)
        rejects(connection, "23514") {
            payment(connection, actor, target, source, type = "REFUND")
        }
        rejects(connection, "23514") {
            payment(connection, actor, source, target, original = original)
        }
        rejects(connection, "23503") {
            payment(connection, actor, target, source, type = "REFUND", original = UUID.randomUUID())
        }
    }

    @Test
    fun `idempotency key scope includes actor and command and its response is coherent`() = transaction { connection ->
        val actor = user(connection)
        val otherActor = user(connection)
        val source = account(connection)
        val target = account(connection)
        val postedPayment = payment(connection, actor, source, target)
        idempotency(connection, actor, "TRANSFER", "shared-key")
        rejects(connection, "23505") { idempotency(connection, actor, "TRANSFER", "shared-key") }
        idempotency(connection, otherActor, "TRANSFER", "shared-key")
        idempotency(connection, actor, "REFUND", "shared-key")
        idempotency(connection, actor, "TRANSFER", "completed", postedPayment, 201, "{\"status\":\"POSTED\"}")
        rejects(connection, "23514") { idempotency(connection, actor, "TRANSFER", "invalid key") }
        rejects(connection, "23514") {
            idempotency(connection, actor, "TRANSFER", "missing-status", postedPayment, null, "{}")
        }
        rejects(connection, "23514") {
            idempotency(connection, actor, "TRANSFER", "array-response", postedPayment, 201, "[]")
        }
    }

    @Test
    fun `ledger references and unique entries reject duplicate or empty movements`() = transaction { connection ->
        val actor = user(connection)
        val source = account(connection)
        val target = account(connection)
        val payment = payment(connection, actor, source, target)
        val tx = UUID.randomUUID()
        execute(connection, "INSERT INTO ledger_transactions (id, payment_id) VALUES (?, ?)", tx, payment)
        rejects(connection, "23505") {
            execute(connection, "INSERT INTO ledger_transactions (id, payment_id) VALUES (?, ?)", UUID.randomUUID(), payment)
        }
        execute(connection, "INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), tx, source, -100L)
        rejects(connection, "23505") {
            execute(connection, "INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tx, source, -100L)
        }
        rejects(connection, "23514") {
            execute(connection, "INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tx, target, 0L)
        }
        rejects(connection, "23503") {
            execute(connection, "INSERT INTO ledger_entries (id, transaction_id, account_id, delta_minor) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), target, 100L)
        }
    }

    @Test
    fun `audit details remain a structured JSON object`() = transaction { connection ->
        val actor = user(connection)
        execute(connection,
            "INSERT INTO audit_events (id, actor_id, event_type, outcome, details) VALUES (?, ?, ?, ?, ?::jsonb)",
            UUID.randomUUID(), actor, "PAYMENT_COMPLETED", "POSTED", "{\"source\":\"test\"}")
        rejects(connection, "23514") {
            execute(connection,
                "INSERT INTO audit_events (id, actor_id, event_type, outcome, details) VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), actor, "PAYMENT_COMPLETED", "POSTED", "[]")
        }
    }

    private fun transaction(block: (Connection) -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    private fun rejects(connection: Connection, sqlState: String, block: () -> Unit) {
        val savepoint = connection.setSavepoint()
        try {
            val exception = assertThrows(SQLException::class.java) { block() }
            assertEquals(sqlState, exception.sqlState)
        } finally {
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
    }

    private fun user(connection: Connection, email: String = "${UUID.randomUUID()}@example.test"): UUID =
        UUID.randomUUID().also { id ->
            execute(connection, "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", id, email, "test-only-hash")
        }

    private fun account(connection: Connection, kind: String = "CUSTOMER", balance: Long = 0, currency: String = "RUB"): UUID =
        UUID.randomUUID().also { id ->
            execute(connection, "INSERT INTO ledger_accounts (id, kind, balance_minor, currency) VALUES (?, ?, ?, ?)",
                id, kind, balance, currency)
        }

    private fun wallet(connection: Connection, user: UUID, account: UUID): UUID = UUID.randomUUID().also { id ->
        execute(connection, "INSERT INTO wallets (id, user_id, account_id) VALUES (?, ?, ?)", id, user, account)
    }

    private fun payment(
        connection: Connection,
        actor: UUID,
        source: UUID,
        target: UUID,
        type: String = "TRANSFER",
        status: String = "POSTED",
        amount: Long = 100,
        currency: String = "RUB",
        original: UUID? = null,
        rejectionCode: String? = if (status == "REJECTED") "INSUFFICIENT_FUNDS" else null,
    ): UUID = UUID.randomUUID().also { id ->
        execute(connection,
            """
            INSERT INTO payments (id, type, status, actor_id, source_account_id, target_account_id,
                                  amount_minor, currency, original_payment_id, rejection_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, type, status, actor, source, target, amount, currency, original, rejectionCode)
    }

    private fun idempotency(
        connection: Connection,
        actor: UUID,
        command: String,
        key: String,
        payment: UUID? = null,
        status: Int? = null,
        body: String? = null,
    ) {
        execute(connection,
            """
            INSERT INTO idempotency_records (id, actor_id, command_type, idempotency_key,
                                             request_fingerprint, payment_id, response_status, response_body)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            UUID.randomUUID(), actor, command, key, "a".repeat(64), payment, status, body)
    }

    private fun execute(connection: Connection, sql: String, vararg values: Any?) {
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
