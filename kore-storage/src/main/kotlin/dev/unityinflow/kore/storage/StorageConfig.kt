package dev.unityinflow.kore.storage

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig

/**
 * Wires R2dbcDatabase (Exposed) and Flyway with their respective connection types.
 *
 * CRITICAL (Pitfall 7, D-18): Do NOT use Spring @Transactional with Exposed.
 * Use only Exposed's own suspendTransaction { } for all database operations.
 *
 * CRITICAL (D-19, D-20): Flyway requires JDBC datasource even though the application
 * uses R2DBC for all runtime access. Supply separate JDBC URL for Flyway.
 * Pattern from RESEARCH.md Pattern 6.
 *
 * @param r2dbcUrl R2DBC connection URL, e.g. "r2dbc:postgresql://user:pass@host:5432/db"
 * @param jdbcUrl JDBC connection URL for Flyway, e.g. "jdbc:postgresql://host:5432/db"
 * @param dbUser Database username — supply via environment variable, never hardcode
 * @param dbPassword Database password — supply via environment variable, never hardcode
 */
class StorageConfig(
    private val r2dbcUrl: String,
    private val jdbcUrl: String,
    private val dbUser: String,
    private val dbPassword: String,
) {
    /** R2DBC database connection for Exposed suspendTransaction blocks. */
    val database: R2dbcDatabase by lazy {
        R2dbcDatabase.connect(
            url = r2dbcUrl,
            databaseConfig =
                R2dbcDatabaseConfig {
                    defaultMaxAttempts = 3
                },
        )
    }

    /**
     * Creates and runs Flyway migration using JDBC datasource.
     * Call once at application startup before accepting traffic.
     */
    fun migrate(): Flyway {
        val flyway =
            Flyway(
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, dbUser, dbPassword)
                    .locations("classpath:db/migration"),
            )
        flyway.migrate()
        return flyway
    }
}
