package io.github.unityinflow.kore.spring

import io.github.unityinflow.kore.core.internal.InMemoryAuditLog
import io.github.unityinflow.kore.core.port.AuditLog
import io.github.unityinflow.kore.storage.PostgresAuditLogAdapter
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * KORE-04 / D-03 hard pre-tag gate — the Exposed-r2dbc-on-Spring-Boot-4 smoke test.
 *
 * Docker-free by design. Uses [ApplicationContextRunner] (not Testcontainers) so it
 * is deterministic and never depends on Docker/Ryuk — the Testcontainers
 * `@Tag("integration")` storage tests stay the optional Layer-2 deep check
 * (phase-07 already hit a Ryuk timeout, so they cannot be the hard gate).
 *
 * Proves that kore's OWN Spring Boot 4 auto-config (`KoreAutoConfiguration`) wires its
 * Exposed-r2dbc storage path on the SB 4.0.5 classpath:
 *
 *  1. With `kore-storage` (and therefore `org.jetbrains.exposed.v1.r2dbc.*`) on the
 *     test classpath, the `@ConditionalOnClass`-gated `StorageAutoConfiguration` fires.
 *  2. A user-supplied [R2dbcDatabase] bean satisfies the `postgresAuditLog`
 *     `@ConditionalOnMissingBean(AuditLog)` factory, so the r2dbc-backed
 *     [PostgresAuditLogAdapter] wins over the [InMemoryAuditLog] fallback.
 *
 * `R2dbcDatabase.connect(...)` is lazy — it registers the connection factory but opens
 * NO socket at bean-creation time, so no live database (and no Docker) is needed for the
 * context to start. EXPOSED-944 does not hit kore (kore never imports the broken
 * `ExposedAutoConfiguration`); this test still catches any OTHER moved-Spring-class
 * surprise in kore's own SB4 wiring as a context-start failure.
 */
class SpringBoot4ExposedSmokeTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            // Prevents the dashboard SmartLifecycle from binding Ktor to port 8090.
            .withPropertyValues("kore.dashboard.enabled=false")

    @Test
    fun `kore auto-config starts on Spring Boot 4 with Exposed-r2dbc on the classpath`() {
        contextRunner
            .withUserConfiguration(R2dbcDatabaseTestConfig::class.java)
            .run { context ->
                // SB4 context actually starts — any moved-Spring-class surprise in kore's
                // own auto-config would surface here as a non-null startupFailure.
                context.startupFailure.shouldBeNull()

                // The Exposed-r2dbc storage gate fired: the r2dbc-backed adapter (not the
                // in-memory fallback) is the AuditLog bean, proving StorageConfig's
                // R2dbcDatabase wiring resolves under SB 4.0.5.
                val auditLog = context.getBean(AuditLog::class.java)
                auditLog.shouldBeInstanceOf<PostgresAuditLogAdapter>()
                auditLog.isPersistent shouldBe true
            }
    }

    @Test
    fun `falls back to InMemoryAuditLog when no R2dbcDatabase bean is supplied`() {
        // kore-storage is on the classpath, but with no R2dbcDatabase bean the
        // postgresAuditLog factory is SKIPPED (@ConditionalOnBean) and the in-memory
        // default wins — the documented graceful-degradation contract. This guards the
        // KoreAutoConfiguration fix that made that contract real (it previously threw
        // UnsatisfiedDependencyException, surfaced by adding kore-storage to the test
        // classpath for the smoke test).
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()
            context.getBean(AuditLog::class.java).shouldBeInstanceOf<InMemoryAuditLog>()
        }
    }

    /**
     * Supplies a lazy [R2dbcDatabase] bean so the Exposed-r2dbc storage gate is fully
     * satisfied without opening a real connection (Docker-free). The r2dbc-postgresql
     * driver is on the test classpath; `R2dbcDatabase.connect(url)` registers the
     * connection lazily and opens NO socket at bean-creation time.
     */
    @Configuration(proxyBeanMethods = false)
    class R2dbcDatabaseTestConfig {
        @Bean
        fun r2dbcDatabase(): R2dbcDatabase =
            R2dbcDatabase.connect(
                url = "r2dbc:postgresql://localhost:5432/kore_smoke_test",
                user = "smoke",
                password = "smoke",
            )
    }
}
