# Phase 2: Observability & Storage - Research

**Researched:** 2026-04-12
**Domain:** OpenTelemetry coroutine context propagation, Micrometer metrics, Exposed R2DBC, Flyway + R2DBC wiring
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**OTel Span Design**
- D-01: 3-level tracing hierarchy: agent run (root) → LLM call (child) → tool use (child of LLM call)
- D-02: Kore-specific span names: `kore.agent.run`, `kore.llm.call`, `kore.tool.use`, `kore.skill.activate` (Phase 3)
- D-03: Common attributes on all spans: `kore.agent.name`, `kore.agent.id`
- D-04: LLM-specific attributes: `kore.llm.model`, `kore.llm.tokens.in`, `kore.llm.tokens.out`, `kore.llm.backend`, `kore.llm.duration_ms`
- D-05: Tool-specific attributes: `kore.tool.name`, `kore.tool.mcp_server`, `kore.tool.duration_ms`
- D-06: Do NOT follow GenAI semantic conventions (experimental, unstable) — use kore namespace

**OTel Context Propagation**
- D-07: OTel wiring lives in kore-observability module, not kore-core
- D-08: `ObservableAgentRunner` wraps core `AgentRunner`, injecting `OpenTelemetryContextElement` into every agent CoroutineScope
- D-09: Uses `opentelemetry-extension-kotlin` library for the context element implementation
- D-10: kore-core remains free of OTel dependencies — observability is a pure adapter

**PostgreSQL Schema**
- D-11: Three tables: `agent_runs`, `llm_calls`, `tool_calls` with FK relationships
- D-12: Core columns are typed (UUID, TEXT, INT, TIMESTAMPTZ). Flexible data in JSONB columns (`metadata`, `arguments`, `result`)
- D-13: `agent_runs`: id, agent_name, task, result_type, started_at, finished_at, metadata
- D-14: `llm_calls`: id, run_id (FK), model, tokens_in, tokens_out, duration_ms, metadata
- D-15: `tool_calls`: id, run_id (FK), llm_call_id (FK), tool_name, mcp_server, duration_ms, arguments, result
- D-16: Append-only pattern — no UPDATE/DELETE operations in the AuditLog implementation

**ORM Choice**
- D-17: Exposed 1.0 DSL API (not DAO) with R2DBC driver for non-blocking writes
- D-18: Avoid Spring `@Transactional` entirely — use Exposed's own `suspendTransaction` to prevent GitHub issue #1722
- D-19: Flyway for all schema migrations (`src/main/resources/db/migration/`)
- D-20: Use `spring-boot-starter-flyway` (not `flyway-core` alone) — Spring Boot 4 requires the starter for auto-configuration

**Micrometer Metrics**
- D-21: Low cardinality design — 4 counters + 1 gauge, 2-3 labels each
- D-22: Counters: `kore.agent.runs` [agent_name, result_type], `kore.llm.calls` [agent_name, model, backend], `kore.tokens.used` [agent_name, model, direction], `kore.errors` [agent_name, error_type]
- D-23: Gauge: `kore.agents.active` [] (no labels)
- D-24: Labels are configured names (not UUIDs) to keep cardinality bounded
- D-25: Per-tool metrics deferred — tool-level detail lives in OTel spans, not Micrometer

### Claude's Discretion
- Connection pooling configuration for R2DBC
- Flyway migration naming convention (V1__description.sql vs V001__description.sql)
- Whether to add indexes on agent_runs.agent_name or llm_calls.model for query performance
- Exact ObservableAgentRunner implementation pattern (decorator vs subclass)

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| OBSV-01 | OTel span on every LLM call with model, token count, duration | D-02, D-04, opentelemetry-extension-kotlin 1.61.0, ObservableAgentRunner pattern |
| OBSV-02 | OTel span on every tool use with tool name, MCP server, duration | D-02, D-05, same wiring pattern as OBSV-01 |
| OBSV-03 | OTel span on every skill activation | D-02 (kore.skill.activate); stub in Phase 2 since skills come in Phase 3 |
| OBSV-04 | OpenTelemetryContextElement on every agent CoroutineScope (context survives suspension) | D-08, D-09, ContextExtensions.kt asContextElement() pattern |
| OBSV-05 | Micrometer metrics: agent_runs_total, llm_calls_total, tokens_used_total, errors_total | D-21 through D-25, MeterRegistry injection pattern |
| STOR-01 | PostgreSQL audit log schema: agent_runs, llm_calls, tool_calls tables | D-11 through D-15, Flyway V1__init_schema.sql |
| STOR-02 | Flyway migrations for all schema changes | D-19, D-20, JDBC-alongside-R2DBC pattern (critical finding) |
| STOR-03 | AuditRepository (append-only) via Exposed R2DBC | D-17, D-18, suspendTransaction DSL insert pattern |
| STOR-04 | Audit logging records every agent run, LLM call, and tool invocation | AuditLog port already called from AgentLoop; PostgresAuditLogAdapter implements the interface |
</phase_requirements>

---

## Summary

Phase 2 builds two independent adapter modules — `kore-observability` and `kore-storage` — that implement existing port interfaces from `kore-core`. Neither module changes the agent loop. Both depend only on kore-core, keeping the hexagonal boundary intact.

The critical technical challenge is **OTel context propagation across coroutine suspension boundaries**. The OTel Java SDK uses `ThreadLocal` for context, but coroutines switch threads on suspension. The solution is `opentelemetry-extension-kotlin`'s `asContextElement()` function, which bridges `ThreadLocal` context into the Kotlin coroutine context. `ObservableAgentRunner` wraps the existing `AgentRunner` (decorator pattern) and injects `OpenTelemetryContextElement` into the `CoroutineScope`. [VERIFIED: oneuptime.com Feb 2026]

The second critical finding is that **Flyway does not support R2DBC natively**. Even though the application uses R2DBC for all runtime database access, Flyway requires a JDBC connection for migrations. This means `kore-storage` must include both the `r2dbc-postgresql` driver (for Exposed runtime) AND the blocking `postgresql` JDBC driver (for Flyway only). The YAML config must supply two different URL schemes: `r2dbc:postgresql://` for the app and `jdbc:postgresql://` for Flyway. [VERIFIED: codersee.com, Spring Boot issues tracker]

Micrometer metrics are straightforward: inject `MeterRegistry`, create the 4 counters + 1 gauge at construction time with fixed tag names, increment on each relevant event. The gauge tracks active agent count via an `AtomicInteger`. All tag values come from configured names (agent name, LLM model) not runtime-generated IDs. [CITED: docs.micrometer.io/micrometer]

**Primary recommendation:** Build kore-observability and kore-storage in parallel (independent modules), use the decorator pattern for `ObservableAgentRunner`, use Testcontainers PostgreSQL (never H2) for all storage tests, and supply JDBC + R2DBC deps side-by-side for the Flyway/Exposed split.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| opentelemetry-extension-kotlin | 1.61.0 | `OpenTelemetryContextElement` / `asContextElement()` | Only official OTel library that bridges ThreadLocal context into coroutine context; required for OBSV-04 |
| opentelemetry-api | via Spring Boot 4 starter BOM | OTel Tracer, Span, Context API | Provided by `spring-boot-starter-opentelemetry`; do not declare version manually |
| micrometer-core | 1.16+ (via Boot 4 BOM) | MeterRegistry, Counter, Gauge | Spring Boot 4 includes Micrometer 1.16; auto-configured |
| exposed-core | 1.0.0 | Exposed DSL table definitions | Stable API since Jan 2026, Kotlin-first, no JPA noise |
| exposed-r2dbc | 1.0.0 | `suspendTransaction`, reactive inserts | R2DBC support is the headline Exposed 1.0 feature; required for non-blocking audit writes |
| r2dbc-postgresql | 1.0.7.RELEASE | Reactive PostgreSQL driver | Standard reactive PostgreSQL driver; used by exposed-r2dbc under the hood |
| postgresql (JDBC) | 42.7.x (via Boot BOM) | JDBC driver for Flyway only | Flyway has no R2DBC support; needs a blocking JDBC connection for migrations |
| flyway-core | 12.3.0 | SQL migration runner | Required for STOR-02 |
| flyway-database-postgresql | 12.3.0 | Flyway PostgreSQL dialect support | Required alongside flyway-core for PostgreSQL targets |
| spring-boot-starter-flyway | via Boot 4 | Flyway Spring Boot auto-config | D-20 explicitly requires the starter, not flyway-core alone |

[VERIFIED: JetBrains Exposed blog Jan 2026, Maven Central sonatype.com, Spring Boot 4 release notes, codersee.com Flyway+R2DBC guide]

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx-coroutines-test | 1.10.2 | `runTest` for suspend function tests | All unit tests in kore-observability and kore-storage |
| testcontainers-postgresql | 1.20+ | Real PostgreSQL in CI | All integration tests in kore-storage — never H2 |
| mockk | 1.14+ | Mocking OTel Tracer / MeterRegistry | Unit tests in kore-observability |
| kotest-assertions-core | 6.1.11 | Fluent assertion matchers | All test files |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| opentelemetry-extension-kotlin | OTel Java Agent with coroutine instrumentation | Agent approach is zero-code but requires JVM agent flag at startup; library approach is explicit and testable — preferred for library modules |
| Exposed R2DBC | Spring Data R2DBC | Spring Data adds annotation-heavy entity layer and `@Transactional` which conflicts with Exposed (Pitfall 7) — Exposed's DSL is cleaner |
| Testcontainers PostgreSQL | H2 in-memory | H2 does not support JSONB, UUID default, `ON CONFLICT` — will cause silent test failures (Pitfall 14) |

**Installation (kore-observability/build.gradle.kts):**
```kotlin
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.otel.extension.kotlin)    // io.opentelemetry:opentelemetry-extension-kotlin:1.61.0
    // OTel API + Micrometer come from spring-boot-starter-opentelemetry (kore-spring provides these)
    // For standalone unit tests in kore-observability, add:
    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

**Installation (kore-storage/build.gradle.kts):**
```kotlin
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.exposed.core)             // org.jetbrains.exposed:exposed-core:1.0.0
    implementation(libs.exposed.r2dbc)            // org.jetbrains.exposed:exposed-r2dbc:1.0.0
    runtimeOnly(libs.r2dbc.postgresql)            // io.r2dbc:r2dbc-postgresql:1.0.7.RELEASE
    runtimeOnly(libs.postgresql.jdbc)             // org.postgresql:postgresql (JDBC, for Flyway)
    implementation(libs.flyway.core)              // org.flywaydb:flyway-core:12.3.0
    implementation(libs.flyway.postgresql)        // org.flywaydb:flyway-database-postgresql:12.3.0
    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)  // org.testcontainers:postgresql:1.20+
}
```

**libs.versions.toml additions required:**
```toml
[versions]
exposed = "1.0.0"
flyway = "12.3.0"
r2dbc-postgresql = "1.0.7.RELEASE"
otel-extension-kotlin = "1.61.0"
testcontainers = "1.20.0"

[libraries]
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-r2dbc = { module = "org.jetbrains.exposed:exposed-r2dbc", version.ref = "exposed" }
r2dbc-postgresql = { module = "io.r2dbc:r2dbc-postgresql", version.ref = "r2dbc-postgresql" }
postgresql-jdbc = { module = "org.postgresql:postgresql" }   # version managed by Spring Boot BOM
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
otel-extension-kotlin = { module = "io.opentelemetry:opentelemetry-extension-kotlin", version.ref = "otel-extension-kotlin" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
```

**settings.gradle.kts additions:**
```kotlin
include(
    "kore-core",
    "kore-mcp",
    "kore-llm",
    "kore-test",
    "kore-observability",   // NEW
    "kore-storage",         // NEW
)
```

---

## Architecture Patterns

### Recommended Project Structure

```
kore-observability/
├── build.gradle.kts
└── src/
    ├── main/kotlin/dev/unityinflow/kore/observability/
    │   ├── ObservableAgentRunner.kt      # Decorator wrapping AgentRunner + OTel scope
    │   ├── KoreTracer.kt                 # Thin wrapper: Tracer + span helper functions
    │   ├── KoreMetrics.kt                # MeterRegistry holder: 4 counters + 1 gauge
    │   └── EventBusMetricsObserver.kt    # Subscribes to EventBus, drives metrics + spans
    └── test/kotlin/dev/unityinflow/kore/observability/
        ├── ObservableAgentRunnerTest.kt
        ├── KoreTracerTest.kt
        └── KoreMetricsTest.kt

kore-storage/
├── build.gradle.kts
└── src/
    ├── main/kotlin/dev/unityinflow/kore/storage/
    │   ├── PostgresAuditLogAdapter.kt    # Implements AuditLog port
    │   ├── tables/
    │   │   ├── AgentRunsTable.kt         # Exposed Table object
    │   │   ├── LlmCallsTable.kt
    │   │   └── ToolCallsTable.kt
    │   └── StorageConfig.kt             # R2dbcDatabase + Flyway bean wiring
    ├── main/resources/db/migration/
    │   └── V1__init_schema.sql           # agent_runs, llm_calls, tool_calls
    └── test/kotlin/dev/unityinflow/kore/storage/
        ├── PostgresAuditLogAdapterTest.kt  # Testcontainers
        └── MigrationTest.kt                # Flyway migration validates
```

---

### Pattern 1: ObservableAgentRunner — Decorator with OTel CoroutineScope

The existing `AgentRunner` in kore-core has a placeholder comment: `// OpenTelemetryContextElement will be added in Phase 2`. `ObservableAgentRunner` creates a new `AgentRunner`-like wrapper that adds `OpenTelemetryContextElement` to the `CoroutineScope`. It does NOT subclass `AgentRunner` (which is a concrete class with a private scope) — it wraps it as a decorator.

**Decision from Claude's Discretion:** Use decorator pattern (wrap, don't subclass).

```kotlin
// Source: ARCHITECTURE.md Pattern 4 + oneuptime.com Feb 2026
// kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/ObservableAgentRunner.kt
package dev.unityinflow.kore.observability

import dev.unityinflow.kore.core.AgentLoop
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job

/**
 * Wraps [AgentLoop] with an OTel-aware CoroutineScope.
 * OpenTelemetryContextElement ensures span context survives coroutine suspension.
 */
class ObservableAgentRunner(
    private val loop: AgentLoop,
    private val tracer: Tracer,
    private val metrics: KoreMetrics,
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        Context.current().asContextElement()   // OTel context propagates across thread switches
    )

    fun run(task: AgentTask): Deferred<AgentResult> =
        scope.async {
            val span = tracer.spanBuilder("kore.agent.run")
                .setAttribute("kore.agent.id", task.id)
                .startSpan()
            span.makeCurrent().use {
                try {
                    metrics.agentsActive.incrementAndGet()
                    val result = loop.run(task)
                    span.setAttribute("kore.result_type", result.typeName())
                    result
                } finally {
                    metrics.agentsActive.decrementAndGet()
                    span.end()
                }
            }
        }

    fun shutdown() {
        scope.coroutineContext.job.cancel()
    }
}
```

[VERIFIED: ContextExtensions.kt source, oneuptime.com OTel+coroutines guide Feb 2026, ARCHITECTURE.md Pattern 4]

---

### Pattern 2: OTel Context Propagation — asContextElement()

The `opentelemetry-extension-kotlin` library provides `Context.asContextElement()` which implements `ThreadContextElement<Context>`. This means when a coroutine suspends on one thread and resumes on another, the OTel context is stored/restored automatically.

```kotlin
// Source: github.com/open-telemetry/opentelemetry-java ContextExtensions.kt
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.context.Context

// At CoroutineScope creation — captures current OTel Context:
val scope = CoroutineScope(
    SupervisorJob() +
    Dispatchers.Default +
    Context.current().asContextElement()
)

// Creating a child span inside a suspend function:
suspend fun withSpan(tracer: Tracer, name: String, block: suspend () -> Unit) {
    val span = tracer.spanBuilder(name)
        .setParent(Context.current())   // inherits from context element
        .startSpan()
    withContext(span.storeInContext(Context.current()).asContextElement()) {
        try {
            block()
        } finally {
            span.end()
        }
    }
}
```

[VERIFIED: opentelemetry-java ContextExtensions.kt source, oneuptime.com guide Feb 2026]

**Critical:** `setParent(Context.current())` must be called at span creation. Without it, the span is a new root. `Context.current()` works because the `asContextElement()` in the coroutine context has restored the thread-local before this suspend resumes.

---

### Pattern 3: KoreMetrics — MeterRegistry with 4 Counters + 1 Gauge

```kotlin
// kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/KoreMetrics.kt
package dev.unityinflow.kore.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger

class KoreMetrics(registry: MeterRegistry) {

    // Gauge: number of currently active agents (goes up/down)
    val agentsActive = AtomicInteger(0).also { active ->
        registry.gauge("kore.agents.active", active)
    }

    // kore.agent.runs [agent_name, result_type]
    fun agentRunCounter(agentName: String, resultType: String): Counter =
        Counter.builder("kore.agent.runs")
            .tag("agent_name", agentName)
            .tag("result_type", resultType)
            .register(registry)

    // kore.llm.calls [agent_name, model, backend]
    fun llmCallCounter(agentName: String, model: String, backend: String): Counter =
        Counter.builder("kore.llm.calls")
            .tag("agent_name", agentName)
            .tag("model", model)
            .tag("backend", backend)
            .register(registry)

    // kore.tokens.used [agent_name, model, direction] — direction = "in" | "out"
    fun tokensUsedCounter(agentName: String, model: String, direction: String): Counter =
        Counter.builder("kore.tokens.used")
            .tag("agent_name", agentName)
            .tag("model", model)
            .tag("direction", direction)
            .register(registry)

    // kore.errors [agent_name, error_type]
    fun errorCounter(agentName: String, errorType: String): Counter =
        Counter.builder("kore.errors")
            .tag("agent_name", agentName)
            .tag("error_type", errorType)
            .register(registry)
}
```

[CITED: docs.micrometer.io/micrometer/reference/concepts/counters.html]

---

### Pattern 4: Exposed R2DBC Table Definition + suspendTransaction Insert

```kotlin
// Source: jetbrains.github.io/Exposed/api/exposed-r2dbc + Exposed CRUD docs
// kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/AgentRunsTable.kt
package dev.unityinflow.kore.storage.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object AgentRunsTable : UUIDTable("agent_runs") {
    val agentName = varchar("agent_name", 255)
    val task      = text("task")
    val resultType = varchar("result_type", 50)
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val metadata  = text("metadata")   // JSONB stored as text in DSL; actual JSONB type via raw SQL in migration
}

// PostgresAuditLogAdapter — insert pattern
suspend fun recordAgentRun(agentId: String, task: AgentTask, result: AgentResult) {
    suspendTransaction(db = database) {
        AgentRunsTable.insert {
            it[id] = UUID.fromString(agentId)
            it[agentName] = task.metadata["agent_name"] ?: "unknown"
            it[AgentRunsTable.task] = task.input
            it[resultType] = result.typeName()
            it[startedAt] = OffsetDateTime.now()
            it[finishedAt] = OffsetDateTime.now()
            it[metadata] = Json.encodeToString(task.metadata)
        }
    }
}
```

**Note on JSONB:** Exposed 1.0 DSL does not have a built-in JSONB column type. The migration SQL uses `JSONB` directly, but the Exposed column declares it as `text()`. The JSON string is serialized/deserialized in the adapter layer using kotlinx.serialization. [ASSUMED — Exposed docs do not explicitly document JSONB column type in 1.0 API]

---

### Pattern 5: PostgreSQL R2DBC Database Connection

```kotlin
// Source: jetbrains.com/help/exposed/working-with-databases.html
val database = R2dbcDatabase.connect(
    url = "r2dbc:postgresql://localhost:5432/kore",
    databaseConfig = R2dbcDatabaseConfig {
        defaultMaxAttempts = 3
    }
)
// Username/password passed via R2DBC URL or environment variables:
// r2dbc:postgresql://user:password@host:5432/dbname
```

[CITED: jetbrains.com/help/exposed/working-with-databases.html]

---

### Pattern 6: Flyway with R2DBC — CRITICAL Split Configuration

Flyway does not support R2DBC. The application uses R2DBC for all runtime access, but Flyway requires a JDBC DataSource for migrations. Both the blocking JDBC driver and the reactive R2DBC driver must be on the classpath.

```yaml
# application.yml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/kore
    username: ${KORE_DB_USER}
    password: ${KORE_DB_PASSWORD}
  flyway:
    url: jdbc:postgresql://localhost:5432/kore
    user: ${KORE_DB_USER}
    password: ${KORE_DB_PASSWORD}
    locations: classpath:db/migration
```

```kotlin
// StorageConfig.kt — manual Flyway bean (Spring Boot 4 with R2DBC needs explicit wiring)
@Configuration
class StorageConfig(
    @Value("\${spring.flyway.url}") private val jdbcUrl: String,
    @Value("\${spring.flyway.user}") private val dbUser: String,
    @Value("\${spring.flyway.password}") private val dbPassword: String,
) {
    @Bean(initMethod = "migrate")
    fun flyway(): Flyway = Flyway(
        Flyway.configure()
            .dataSource(jdbcUrl, dbUser, dbPassword)
            .locations("classpath:db/migration")
    )
}
```

[VERIFIED: codersee.com Flyway+WebFlux guide, spring-projects/spring-boot issue #40325]

---

### Pattern 7: Flyway Migration File

```sql
-- kore-storage/src/main/resources/db/migration/V1__init_schema.sql
CREATE TABLE IF NOT EXISTS agent_runs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_name  TEXT        NOT NULL,
    task        TEXT        NOT NULL,
    result_type TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    metadata    JSONB       NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS llm_calls (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      UUID        NOT NULL REFERENCES agent_runs(id),
    model       TEXT        NOT NULL,
    tokens_in   INTEGER     NOT NULL DEFAULT 0,
    tokens_out  INTEGER     NOT NULL DEFAULT 0,
    duration_ms INTEGER     NOT NULL DEFAULT 0,
    metadata    JSONB       NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS tool_calls (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      UUID        NOT NULL REFERENCES agent_runs(id),
    llm_call_id UUID        REFERENCES llm_calls(id),
    tool_name   TEXT        NOT NULL,
    mcp_server  TEXT,
    duration_ms INTEGER     NOT NULL DEFAULT 0,
    arguments   JSONB       NOT NULL DEFAULT '{}',
    result      JSONB       NOT NULL DEFAULT '{}'
);
```

**Naming convention (Claude's Discretion):** Use `V1__description.sql` (not zero-padded) — sufficient for a small schema, consistent with Flyway defaults.

**Indexes (Claude's Discretion):** Add index on `agent_runs.agent_name` and `llm_calls.run_id`. These support the most common audit queries (filter by agent, join runs→calls). Agent name is low-cardinality and safe.

```sql
CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_name ON agent_runs(agent_name);
CREATE INDEX IF NOT EXISTS idx_llm_calls_run_id ON llm_calls(run_id);
CREATE INDEX IF NOT EXISTS idx_tool_calls_run_id ON tool_calls(run_id);
```

[ASSUMED — index design based on expected query patterns; no specific benchmark evidence]

---

### Pattern 8: Testcontainers PostgreSQL for Storage Tests

```kotlin
// Source: PITFALLS.md Pitfall 14 + testcontainers docs
@Testcontainers
class PostgresAuditLogAdapterTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("kore_test")
            withUsername("kore")
            withPassword("kore")
        }
    }

    private lateinit var database: R2dbcDatabase
    private lateinit var adapter: PostgresAuditLogAdapter

    @BeforeAll
    fun setup() {
        // Flyway first (JDBC)
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()

        // Then R2DBC for adapter
        database = R2dbcDatabase.connect(
            url = "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}",
        )
        adapter = PostgresAuditLogAdapter(database)
    }
}
```

[VERIFIED: PITFALLS.md Pitfall 14, testcontainers official docs]

---

### Anti-Patterns to Avoid

- **Using `@Transactional` with Exposed `suspendTransaction`:** Spring's `@Transactional` and Exposed's transaction manager fight over the same JDBC/R2DBC connection. Pick one. D-18 locks this: Exposed's `suspendTransaction` only, zero `@Transactional`. (Pitfall 7)
- **Using H2 for Flyway integration tests:** H2 does not support `JSONB`, `gen_random_uuid()`, or `ON CONFLICT`. The migration will fail or differ from production. Use Testcontainers PostgreSQL. (Pitfall 14)
- **Creating a new `OpenTelemetryContextElement()` without capturing current context:** `Context.current().asContextElement()` captures the current trace context. `OpenTelemetryContextElement()` with no argument starts fresh, breaking parent-child span relationships.
- **Placing OTel imports in kore-core:** D-10 prohibits this. OTel belongs only in kore-observability. kore-core has zero OTel dependencies.
- **Using `counter.increment()` with user/task IDs as tag values:** Creates unbounded cardinality. D-24 mandates agent_name (configured string) not agent_id (UUID). (Pitfall from D-21 design)

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| ThreadLocal → coroutine context bridge | Custom CoroutineContext.Element for OTel | `opentelemetry-extension-kotlin:1.61.0` | Edge cases: context restore on cancellation, nested spans, multiple OTel contexts |
| SQL migrations | Kotlin code to run DDL at startup | Flyway 12.3.0 | Versioning, checksums, baseline, repair — hand-rolled migrations lose all of these |
| PostgreSQL JDBC connection pool | Custom DataSource | HikariCP (via Spring Boot BOM) | Flyway uses HikariCP from the Spring Boot starter automatically |
| Metrics registry | Custom counters map | Micrometer MeterRegistry | Prometheus exposition, JMX, OTel bridge — all come free with Micrometer |
| Span-based timing | Manual `System.currentTimeMillis()` diff | OTel span with `span.end()` | OTel spans record wall time, CPU time, and duration atomically with no clock drift risk |

---

## Runtime State Inventory

This is a new module phase (kore-observability + kore-storage are net-new). No rename/refactor.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — no PostgreSQL database exists yet | Create via Flyway V1 migration |
| Live service config | None — no deployed kore instance yet | None |
| OS-registered state | None | None |
| Secrets/env vars | KORE_DB_USER, KORE_DB_PASSWORD will need to be set | Document in README; never commit |
| Build artifacts | None | None |

---

## Common Pitfalls

### Pitfall 1: OTel Context Lost Across Coroutine Suspension (Pitfall 3 from PITFALLS.md)
**What goes wrong:** `Context.current()` returns root context after a suspension boundary because ThreadLocal was wiped when the coroutine moved threads.
**Why it happens:** OTel Java SDK defaults to ThreadLocal propagation. Coroutines violate the thread-per-request assumption.
**How to avoid:** Every `CoroutineScope` that emits spans must include `Context.current().asContextElement()`. This is set in `ObservableAgentRunner`'s scope — all agent coroutines inherit it.
**Warning signs:** OTel backend shows LLM call spans as root spans instead of children of agent run spans. `span.spanContext().traceId` differs between parent and child.

### Pitfall 2: Exposed + Spring @Transactional Conflict (Pitfall 7 from PITFALLS.md)
**What goes wrong:** D-18 says use `suspendTransaction` exclusively. If any Spring bean in the kore-storage module also has `@Transactional`, two transaction managers compete. Audit writes appear to succeed but silently roll back.
**How to avoid:** Search the entire kore-storage module for `@Transactional` before every merge. Never import `org.springframework.transaction.annotation.Transactional` in kore-storage.
**Warning signs:** Exposed GitHub issue #1722 documents the exact symptom. Test: write a record, assert it exists — if it's missing, this pitfall is active.

### Pitfall 3: Flyway on R2DBC Classpath Only
**What goes wrong:** `runtimeOnly("io.r2dbc:r2dbc-postgresql")` added but blocking `postgresql` JDBC driver omitted. Flyway cannot find a DataSource and throws `FlywayException: Unable to connect`.
**How to avoid:** kore-storage `build.gradle.kts` must include BOTH `runtimeOnly(libs.r2dbc.postgresql)` AND `runtimeOnly(libs.postgresql.jdbc)`.
**Warning signs:** Application starts but prints `Flyway - Unable to determine dialect` or `No DataSource configured`.

### Pitfall 4: Testcontainers @ServiceConnection with Spring Boot 4 Known Issue
**What goes wrong:** `@ServiceConnection` auto-detection fails with `ConnectionDetailsFactoryNotFoundException` in Spring Boot 4.
**How to avoid:** Use `@DynamicPropertySource` as the fallback wiring pattern for tests, or add explicit `name` parameter to `@ServiceConnection`. Do NOT rely on auto-detection until fixed upstream. [VERIFIED: spring-projects/spring-boot issue #48234]

### Pitfall 5: Creating OTel Spans Outside the Agent Scope
**What goes wrong:** `kore-observability` creates a span for an operation that happens in kore-core, but the span context does not propagate because kore-core has no OTel dep.
**Why it happens:** The architecture is correct (D-10: kore-core is OTel-free) but the implication is that all span boundaries must be in `ObservableAgentRunner` or `EventBusMetricsObserver`, not in kore-core.
**How to avoid:** kore-core emits `AgentEvent` lifecycle events. `ObservableAgentRunner` subscribes to these and opens/closes spans based on events. This keeps OTel out of kore-core entirely.

---

## Code Examples

### Creating a Child Span for LLM Call

```kotlin
// Source: oneuptime.com OTel+Kotlin guide Feb 2026, ContextExtensions.kt
suspend fun traceLlmCall(
    tracer: Tracer,
    agentId: String,
    model: String,
    backend: String,
    block: suspend () -> Unit,
) {
    val span = tracer.spanBuilder("kore.llm.call")
        .setParent(Context.current())   // inherits from ObservableAgentRunner scope
        .setAttribute("kore.agent.id", agentId)
        .setAttribute("kore.llm.model", model)
        .setAttribute("kore.llm.backend", backend)
        .startSpan()
    val startMs = System.currentTimeMillis()
    withContext(span.storeInContext(Context.current()).asContextElement()) {
        try {
            block()
        } finally {
            span.setAttribute("kore.llm.duration_ms", System.currentTimeMillis() - startMs)
            span.end()
        }
    }
}
```

### Exposed R2DBC Full Insert Example

```kotlin
// Source: jetbrains.github.io/Exposed/api/exposed-r2dbc suspendTransaction + DSL CRUD docs
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class PostgresAuditLogAdapter(private val database: R2dbcDatabase) : AuditLog {

    override suspend fun recordAgentRun(agentId: String, task: AgentTask, result: AgentResult) {
        suspendTransaction(db = database) {
            AgentRunsTable.insert {
                it[id] = UUID.fromString(agentId)
                it[agentName] = task.metadata["agent_name"] ?: "unknown"
                it[AgentRunsTable.task] = task.input
                it[resultType] = result.typeName()
                it[startedAt] = OffsetDateTime.now()
                it[finishedAt] = OffsetDateTime.now()
                it[metadata] = Json.encodeToString(task.metadata)
            }
        }
    }

    override suspend fun recordLLMCall(agentId: String, backend: String, usage: TokenUsage) {
        // Pattern identical — suspendTransaction { LlmCallsTable.insert { ... } }
    }

    override suspend fun recordToolCall(agentId: String, call: ToolCall, result: ToolResult) {
        // Pattern identical — suspendTransaction { ToolCallsTable.insert { ... } }
    }
}
```

### AgentResult.typeName() Extension (needed by both modules)

```kotlin
// Place in kore-core as an extension (keeps sealed class knowledge in core)
fun AgentResult.typeName(): String = when (this) {
    is AgentResult.Success -> "success"
    is AgentResult.BudgetExceeded -> "budget_exceeded"
    is AgentResult.ToolError -> "tool_error"
    is AgentResult.LLMError -> "llm_error"
    is AgentResult.Cancelled -> "cancelled"
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Spring Sleuth for tracing | Micrometer Tracing + OTel bridge (Boot 4) | Spring Boot 4 release, Nov 2025 | Sleuth is dead; use spring-boot-starter-opentelemetry |
| spring-boot-starter-opentelemetry (community) | spring-boot-starter-opentelemetry (official, Boot 4) | Nov 2025 | Was a separate artifact; now first-party in Boot 4 |
| Exposed 0.x (breaking changes) | Exposed 1.0 (stable API, no breaking until 2.0) | Jan 2026 | Stable target; R2DBC support is GA, not experimental |
| Flyway + JDBC only | Flyway (JDBC only) + R2DBC for app runtime | Ongoing | Flyway will never support R2DBC; dual-driver pattern is the permanent solution |
| newSuspendedTransaction (Exposed 0.x) | suspendTransaction (Exposed 1.0 R2DBC module) | Jan 2026 | Function renamed in 1.0; import from `org.jetbrains.exposed.v1.r2dbc.transactions` |

**Deprecated/outdated:**
- `spring-cloud-sleuth`: Removed. Replaced by Micrometer Tracing in Spring Boot 3+.
- `opentelemetry-spring-boot-starter` (community artifact, group `io.opentelemetry.instrumentation`): Replaced by official `spring-boot-starter-opentelemetry` in Boot 4. Do not add the community starter as an extra dependency.
- `newSuspendedTransaction` (Exposed 0.x): Renamed to `suspendTransaction` in Exposed 1.0 R2DBC module.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Exposed 1.0 does not have a native JSONB column type in DSL; use `text()` and store JSON strings | Architecture Patterns (Pattern 4) | If Exposed 1.0 added JSONB support, a cleaner column definition is possible — no functional impact |
| A2 | Indexes on agent_runs.agent_name and llm_calls.run_id are the right choices for query performance | Pattern 7 (SQL migration) | If access patterns differ from expected, wrong indexes waste storage — low risk for v0.1 audit volume |
| A3 | `opentelemetry-extension-kotlin:1.61.0` is version-compatible with the OTel API version bundled in Spring Boot 4.0.5 | Standard Stack | Version mismatch could cause `NoSuchMethodError` at runtime — verify BOM alignment before building |

---

## Open Questions

1. **opentelemetry-extension-kotlin version vs Spring Boot 4 BOM alignment**
   - What we know: Spring Boot 4.0.5 manages OTel API via its BOM. `opentelemetry-extension-kotlin:1.61.0` has its own transitive OTel API dependency.
   - What's unclear: Whether 1.61.0 is aligned with the OTel API version Spring Boot 4.0.5 specifies, or whether we need to override the version in the version catalog.
   - Recommendation: On first build, run `./gradlew dependencyInsight --dependency opentelemetry-api --configuration runtimeClasspath` in kore-observability. If two versions appear, pin `otel-extension-kotlin` to match the Boot 4 BOM's OTel API version.

2. **Exposed R2DBC JSONB column support**
   - What we know: Exposed 1.0 documentation and examples show varchar/text/int/uuid column types. JSONB is PostgreSQL-specific.
   - What's unclear: Whether `exposed-r2dbc:1.0.0` ships a `jsonb()` column type or requires the text workaround.
   - Recommendation: Check `org.jetbrains.exposed.v1.r2dbc.postgresql` package on first use. If no `jsonb()` exists, use `text()` + serialize/deserialize in adapter. The migration SQL uses `JSONB` regardless — the column type in Exposed is only the Kotlin-side accessor.

3. **Spring Boot 4 @ServiceConnection workaround for Testcontainers**
   - What we know: Issue #48234 was open as of research date. `@DynamicPropertySource` is the fallback.
   - What's unclear: Whether the issue is fixed in Boot 4.0.5 or still requires workaround.
   - Recommendation: Write tests using `@DynamicPropertySource` from the start. If `@ServiceConnection` starts working, it's a refactor with no behavioral change.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java (JVM) | Build and test | Yes | OpenJDK 24.0.2 (Corretto) — above JVM 21 minimum | None needed |
| Gradle | Build | Yes | 9.1.0 (wrapper in repo) | None needed |
| Docker | Testcontainers PostgreSQL | Yes | 28.5.1, daemon running | None — Testcontainers requires Docker |
| PostgreSQL (local) | Development run | No (no local install) | — | Use Docker Compose or Testcontainers for dev testing |

**Note:** JVM 24 is above the project's JVM 21 target. Kotlin `jvmToolchain(21)` in `build.gradle.kts` ensures bytecode targets JVM 21 regardless of host JVM version — no issue. [VERIFIED: kore-core/build.gradle.kts]

**Docker is running** with existing containers — no conflict with Testcontainers. Testcontainers will create its own PostgreSQL container.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (5.12.0) + Kotest assertions (6.1.11) |
| Config file | `tasks.test { useJUnitPlatform() }` in each module's build.gradle.kts |
| Quick run command | `./gradlew :kore-observability:test :kore-storage:test` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OBSV-01 | LLM call span has model, tokens, duration attributes | unit | `./gradlew :kore-observability:test --tests "*.KoreTracerTest"` | Wave 0 |
| OBSV-02 | Tool call span has tool_name, mcp_server, duration attributes | unit | `./gradlew :kore-observability:test --tests "*.KoreTracerTest"` | Wave 0 |
| OBSV-03 | kore.skill.activate span name registered (no activation yet) | unit (stub) | `./gradlew :kore-observability:test` | Wave 0 |
| OBSV-04 | Span context survives coroutine suspension across thread switch | unit | `./gradlew :kore-observability:test --tests "*.ObservableAgentRunnerTest"` | Wave 0 |
| OBSV-05 | 4 counters + 1 gauge registered with correct names and tags | unit | `./gradlew :kore-observability:test --tests "*.KoreMetricsTest"` | Wave 0 |
| STOR-01 | agent_runs, llm_calls, tool_calls tables exist after migration | integration | `./gradlew :kore-storage:test --tests "*.MigrationTest"` | Wave 0 |
| STOR-02 | Flyway runs V1 migration successfully | integration | `./gradlew :kore-storage:test --tests "*.MigrationTest"` | Wave 0 |
| STOR-03 | insert via suspendTransaction writes row, verify select | integration | `./gradlew :kore-storage:test --tests "*.PostgresAuditLogAdapterTest"` | Wave 0 |
| STOR-04 | recordAgentRun / recordLLMCall / recordToolCall produce rows | integration | `./gradlew :kore-storage:test --tests "*.PostgresAuditLogAdapterTest"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :kore-observability:test :kore-storage:test`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `kore-observability/src/test/kotlin/.../ObservableAgentRunnerTest.kt` — covers OBSV-04
- [ ] `kore-observability/src/test/kotlin/.../KoreTracerTest.kt` — covers OBSV-01, OBSV-02, OBSV-03
- [ ] `kore-observability/src/test/kotlin/.../KoreMetricsTest.kt` — covers OBSV-05
- [ ] `kore-storage/src/test/kotlin/.../MigrationTest.kt` — covers STOR-01, STOR-02
- [ ] `kore-storage/src/test/kotlin/.../PostgresAuditLogAdapterTest.kt` — covers STOR-03, STOR-04
- [ ] `kore-observability/build.gradle.kts` — new module, does not exist
- [ ] `kore-storage/build.gradle.kts` — new module, does not exist
- [ ] `settings.gradle.kts` updated to include both new modules

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | No user-facing auth in these modules |
| V3 Session Management | No | Stateless audit writes |
| V4 Access Control | No | Internal module, no external API |
| V5 Input Validation | Yes | `agent_name`, `result_type` values going to DB — use parameterized queries (Exposed DSL handles this) |
| V6 Cryptography | No | No encryption at rest needed for audit log in v0.1 |

### Known Threat Patterns for Exposed DSL + PostgreSQL

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL injection via agent task input | Tampering | Exposed DSL uses parameterized queries by default — never string interpolate into SQL |
| Unbounded JSONB growth | Denial of Service | Enforce max size on task.input (e.g., 64KB) at the AuditLog call site or in PostgreSQL check constraint |
| Sensitive data in audit log (API keys in task input) | Information Disclosure | AgentTask.input may contain prompts with secrets — document that audit log is sensitive; do not expose via unauthed endpoints |

---

## Sources

### Primary (HIGH confidence)
- [Exposed 1.0 Released — JetBrains Kotlin Blog, Jan 2026](https://blog.jetbrains.com/kotlin/2026/01/exposed-1-0-is-now-available/) — Exposed 1.0 stable, R2DBC GA
- [Exposed R2DBC suspendTransaction API docs](https://jetbrains.github.io/Exposed/api/exposed-r2dbc/org.jetbrains.exposed.v1.r2dbc.transactions/suspend-transaction.html) — exact function signature
- [opentelemetry-extension-kotlin Maven Central](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-extension-kotlin) — version 1.61.0 confirmed
- [OTel + Kotlin Coroutines Spring Boot — OneUptime, Feb 2026](https://oneuptime.com/blog/post/2026-02-06-opentelemetry-kotlin-coroutines-spring-boot/view) — CoroutineScope + asContextElement pattern
- [Flyway with Spring WebFlux (R2DBC) — codersee.com](https://blog.codersee.com/flyway-spring-webflux/) — JDBC+R2DBC dual-driver configuration
- [Exposed Working with Databases — JetBrains official](https://www.jetbrains.com/help/exposed/working-with-databases.html) — R2dbcDatabase.connect() signature
- [Spring Boot 4.0 Migration Guide — GitHub](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) — flyway starter requirement

### Secondary (MEDIUM confidence)
- [Kotlin Coroutines and OTel Tracing — Nicolas Frankel blog](https://blog.frankel.ch/kotlin-coroutines-otel-tracing/) — coroutine context propagation mechanics
- [Testcontainers Spring Boot Postgres issue #48234](https://github.com/spring-projects/spring-boot/issues/48234) — @ServiceConnection Boot 4 workaround
- [Exposed + Spring TX conflict — GitHub issue #1722](https://github.com/JetBrains/Exposed/issues/1722) — don't mix @Transactional with suspendTransaction
- [Micrometer Counters official docs](https://docs.micrometer.io/micrometer/reference/concepts/counters.html) — Counter.builder pattern

### Tertiary (LOW confidence)
- [Flyway R2DBC Spring Boot — Baeldung](https://www.baeldung.com/spring-r2dbc-flyway) — supplementary Flyway R2DBC configuration notes

---

## Metadata

**Confidence breakdown:**
- Standard stack (Exposed 1.0, Flyway 12, OTel ext): HIGH — official docs verified
- Architecture (OTel context propagation, decorator pattern): HIGH — verified February 2026 source
- Flyway + R2DBC dual-driver pattern: HIGH — verified against official Spring issue tracker and tutorial
- Pitfalls (Exposed/Spring TX conflict, Testcontainers Boot 4): HIGH — verified against GitHub issues
- JSONB column in Exposed DSL: LOW — not explicitly documented, marked ASSUMED

**Research date:** 2026-04-12
**Valid until:** 2026-07-12 (90 days — Exposed 1.0 is stable, OTel Java SDK on rapid release cycle — check extension-kotlin version before building)
