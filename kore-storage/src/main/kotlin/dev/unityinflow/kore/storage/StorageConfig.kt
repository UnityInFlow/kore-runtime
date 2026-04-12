package dev.unityinflow.kore.storage

import dev.unityinflow.kore.storage.tables.JsonbTypeMapper
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.mappers.R2dbcRegistryTypeMapping

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
        val options = ConnectionFactoryOptions.parse(r2dbcUrl)
        val connectionFactory = ConnectionFactories.get(options)
        val typeMapping =
            (R2dbcRegistryTypeMapping.default() as R2dbcRegistryTypeMapping)
                .register(JsonbTypeMapper())
        val config =
            R2dbcDatabaseConfig {
                defaultMaxAttempts = 3
                connectionFactoryOptions = options
                this.typeMapping = typeMapping
            }
        R2dbcDatabase.connect(connectionFactory, config)
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
