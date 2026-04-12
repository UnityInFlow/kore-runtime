package dev.unityinflow.kore.storage

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class MigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("kore_test")
                withUsername("kore")
                withPassword("kore")
            }

        @JvmStatic
        @BeforeAll
        fun setup() {
            StorageConfig(
                r2dbcUrl = "r2dbc:postgresql://kore:kore@${postgres.host}:${postgres.firstMappedPort}/kore_test",
                jdbcUrl = postgres.jdbcUrl,
                dbUser = postgres.username,
                dbPassword = postgres.password,
            ).migrate()
        }
    }

    @Test
    fun `migration creates all three audit tables`() {
        postgres.createConnection("").use { conn ->
            val tables = mutableListOf<String>()
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' ORDER BY table_name",
                )
            while (rs.next()) {
                tables.add(rs.getString("table_name"))
            }
            tables.contains("agent_runs") shouldBe true
            tables.contains("llm_calls") shouldBe true
            tables.contains("tool_calls") shouldBe true
        }
    }

    @Test
    fun `agent_runs metadata column is jsonb type`() {
        postgres.createConnection("").use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name = 'agent_runs' AND column_name = 'metadata'",
                )
            rs.next() shouldBe true
            rs.getString("data_type") shouldBe "jsonb"
        }
    }

    @Test
    fun `migration is idempotent — running twice applies zero migrations on second run`() {
        val config =
            StorageConfig(
                r2dbcUrl = "r2dbc:postgresql://kore:kore@${postgres.host}:${postgres.firstMappedPort}/kore_test",
                jdbcUrl = postgres.jdbcUrl,
                dbUser = postgres.username,
                dbPassword = postgres.password,
            )
        // First migration already done in @BeforeAll — second call should apply 0 new migrations
        val flyway = config.migrate()
        flyway shouldNotBe null
        val info = flyway.info()
        // All migrations should be in "success" state, none "pending"
        val pendingCount = info.pending().size
        pendingCount shouldBe 0
    }
}
