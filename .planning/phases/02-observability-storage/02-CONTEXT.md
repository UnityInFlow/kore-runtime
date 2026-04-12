# Phase 2: Observability & Storage - Context

**Gathered:** 2026-04-12
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement the observability and durable storage adapters: OpenTelemetry spans on every LLM call, tool use, and skill activation; Micrometer metrics counters and gauges; PostgreSQL audit log with Flyway migrations; and the OTel context propagation fix for coroutine suspension boundaries. These are pure adapter implementations of kore-core's existing port interfaces (AuditLog, EventBus). Two new modules: kore-observability and kore-storage.

</domain>

<decisions>
## Implementation Decisions

### OTel Span Design
- **D-01:** 3-level tracing hierarchy: agent run (root) → LLM call (child) → tool use (child of LLM call)
- **D-02:** Kore-specific span names: `kore.agent.run`, `kore.llm.call`, `kore.tool.use`, `kore.skill.activate` (Phase 3)
- **D-03:** Common attributes on all spans: `kore.agent.name`, `kore.agent.id`
- **D-04:** LLM-specific attributes: `kore.llm.model`, `kore.llm.tokens.in`, `kore.llm.tokens.out`, `kore.llm.backend`, `kore.llm.duration_ms`
- **D-05:** Tool-specific attributes: `kore.tool.name`, `kore.tool.mcp_server`, `kore.tool.duration_ms`
- **D-06:** Do NOT follow GenAI semantic conventions (experimental, unstable) — use kore namespace

### OTel Context Propagation
- **D-07:** OTel wiring lives in kore-observability module, not kore-core
- **D-08:** `ObservableAgentRunner` wraps core `AgentRunner`, injecting `OpenTelemetryContextElement` into every agent CoroutineScope
- **D-09:** Uses `opentelemetry-extension-kotlin` library for the context element implementation
- **D-10:** kore-core remains free of OTel dependencies — observability is a pure adapter

### PostgreSQL Schema
- **D-11:** Three tables: `agent_runs`, `llm_calls`, `tool_calls` with FK relationships
- **D-12:** Core columns are typed (UUID, TEXT, INT, TIMESTAMPTZ). Flexible data in JSONB columns (`metadata`, `arguments`, `result`)
- **D-13:** `agent_runs`: id, agent_name, task, result_type, started_at, finished_at, metadata
- **D-14:** `llm_calls`: id, run_id (FK), model, tokens_in, tokens_out, duration_ms, metadata
- **D-15:** `tool_calls`: id, run_id (FK), llm_call_id (FK), tool_name, mcp_server, duration_ms, arguments, result
- **D-16:** Append-only pattern — no UPDATE/DELETE operations in the AuditLog implementation

### ORM Choice
- **D-17:** Exposed 1.0 DSL API (not DAO) with R2DBC driver for non-blocking writes
- **D-18:** Avoid Spring `@Transactional` entirely — use Exposed's own `newSuspendedTransaction` to prevent GitHub issue #1722
- **D-19:** Flyway for all schema migrations (`src/main/resources/db/migration/`)
- **D-20:** Use `spring-boot-starter-flyway` (not `flyway-core` alone) — Spring Boot 4 requires the starter for auto-configuration

### Micrometer Metrics
- **D-21:** Low cardinality design — 4 counters + 1 gauge, 2-3 labels each
- **D-22:** Counters: `kore.agent.runs` [agent_name, result_type], `kore.llm.calls` [agent_name, model, backend], `kore.tokens.used` [agent_name, model, direction], `kore.errors` [agent_name, error_type]
- **D-23:** Gauge: `kore.agents.active` [] (no labels)
- **D-24:** Labels are configured names (not UUIDs) to keep cardinality bounded
- **D-25:** Per-tool metrics deferred — tool-level detail lives in OTel spans, not Micrometer

### Claude's Discretion
- Connection pooling configuration for R2DBC
- Flyway migration naming convention (V1__description.sql vs V001__description.sql)
- Whether to add indexes on agent_runs.agent_name or llm_calls.model for query performance
- Exact ObservableAgentRunner implementation pattern (decorator vs subclass)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Spec
- `08-kore-runtime.md` — Full feature spec, module structure
- `.planning/PROJECT.md` — Core value, constraints

### Research (verified 2026-04-10)
- `.planning/research/STACK.md` — Exposed 1.0 R2DBC, Flyway 12.x, Spring Boot 4 OTel starter versions
- `.planning/research/ARCHITECTURE.md` — Component boundaries, OTel context propagation pattern with code example
- `.planning/research/PITFALLS.md` — Pitfall 3 (OTel context loss), Pitfall 7 (Exposed + Spring TX conflict), Pitfall 12 (Flyway H2 dialect)

### Phase 1 Code (existing)
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt` — Port interface to implement
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt` — EventBus port (observable events)
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt` — Where spans originate
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentRunner.kt` — Where ObservableAgentRunner wraps

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `AuditLog` port interface: `recordAgentRun`, `recordLlmCall`, `recordToolCall` — already called from AgentLoop
- `EventBus` port + `InProcessEventBus`: agent lifecycle events already flowing via SharedFlow
- `AgentRunner`: the wrapping target for `ObservableAgentRunner`
- `AgentResult` sealed class: all 5 variants (Success, BudgetExceeded, ToolError, LLMError, Cancelled) for result_type mapping
- `TokenUsage` data class: tokens_in/out already tracked per LLM call

### Established Patterns
- Hexagonal architecture: ports in kore-core, adapters in separate modules
- All async via coroutines — R2DBC fits naturally
- `val` only, sealed classes for domain modeling

### Integration Points
- kore-observability depends on kore-core (AgentRunner, AgentResult, AgentEvent)
- kore-storage depends on kore-core (AuditLog port, domain types)
- Both modules are independent of each other — can be built in parallel

</code_context>

<specifics>
## Specific Ideas

- OTel spans should be immediately useful in Jaeger/Tempo without custom dashboards — standard attributes, meaningful span names
- The PostgreSQL audit log is append-only by design — never update or delete records
- Micrometer metrics should work out of the box with Prometheus scraping (the standard for Spring Boot apps)

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-observability-storage*
*Context gathered: 2026-04-12*
