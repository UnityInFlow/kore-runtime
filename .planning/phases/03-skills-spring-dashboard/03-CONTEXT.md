# Phase 3: Skills, Spring & Dashboard - Context

**Gathered:** 2026-04-12
**Status:** Ready for planning

<domain>
## Phase Boundary

Deliver the developer experience layer: (1) YAML-based skills engine with pattern activation in a new `kore-skills` module, (2) Spring Boot starter in a new `kore-spring` module providing zero-config auto-configuration for all kore modules, and (3) HTMX dashboard in a new `kore-dashboard` module showing active agents, recent runs, and token costs. Success means: add one Gradle dependency, write an `agent { }` block, get a running agent with skills, observability, and a live dashboard. No additional wiring required.

</domain>

<decisions>
## Implementation Decisions

### Skills Engine

#### YAML Schema
- **D-01:** Skills are 100% declarative YAML — no code, no Kotlin class references
- **D-02:** Required fields: `name`, `description`, `version`, `activation`, `prompt`
- **D-03:** `activation.task_matches` — list of regex patterns (with `(?i)` for case-insensitive). Skill activates if ANY pattern matches the task content
- **D-04:** `activation.requires_tools` (optional) — list of tool names. Skill only activates if ALL listed tools are available in the agent
- **D-05:** `prompt` — multi-line YAML string injected into LLM system message when skill activates

#### Loader Sources
- **D-06:** Skills loaded from TWO sources: classpath (`META-INF/kore/skills/*.yaml`) AND filesystem (configurable directory, default `./kore-skills/`)
- **D-07:** Filesystem skills override classpath skills with the same name (user skills win)
- **D-08:** YAML parsing via `kotlinx.serialization` with YAML format adapter OR snakeyaml if needed

#### Integration
- **D-09:** `SkillRegistry` port interface in kore-core — kore-skills provides the adapter
- **D-10:** Agent loop calls `SkillRegistry.activateFor(task)` before the first LLM call to inject matching skill prompts into system message
- **D-11:** Skill activation emits `kore.skill.activate` span (wires up OBSV-03 from Phase 2)

### Spring Boot Auto-Configuration

#### Agent Registration
- **D-12:** Users register agents via `@Bean` functions returning `AgentRunner` from DSL — idiomatic Spring DI
- **D-13:** No central `KoreAgentRegistry` — Spring's own bean discovery IS the registry
- **D-14:** Users inject `AgentRunner` (or its specific bean) into controllers/services like any other Spring bean

#### Auto-Created Beans (from classpath detection)
- **D-15:** LLM backend beans from `application.yml` keys under `kore.llm.*` (ClaudeBackend, OpenAiBackend, etc.)
- **D-16:** `McpConnectionManager` bean from `kore.mcp.servers` list
- **D-17:** `BudgetEnforcer` bean — `InMemoryBudgetEnforcer` by default (real budget-breaker is a v2 swap)
- **D-18:** `AuditLog` bean — `PostgresAuditLogAdapter` if kore-storage on classpath, else `InMemoryAuditLog`
- **D-19:** `ObservableAgentRunner` wrapper auto-activates if kore-observability on classpath
- **D-20:** `SkillRegistry` bean loading from classpath + `kore.skills.directory` config
- **D-21:** Dashboard router auto-registers at `kore.dashboard.path` (default `/kore`) if kore-dashboard on classpath

#### Configuration Properties
- **D-22:** All config lives under `kore.*` namespace in application.yml, bound via `@ConfigurationProperties("kore")`
- **D-23:** Core sections: `kore.llm.*`, `kore.mcp.servers[]`, `kore.skills.directory`, `kore.storage.*`, `kore.dashboard.*`, `kore.budget.*`

### HTMX Dashboard

#### Data Source Strategy
- **D-24:** Hybrid: in-memory for real-time state, PostgreSQL for historical data
- **D-25:** `EventBusDashboardObserver` subscribes to EventBus, maintains `ConcurrentHashMap<AgentId, AgentState>` for active agents
- **D-26:** Recent runs and cost summary query PostgreSQL via `AuditLog.queryRecentRuns()` and `AuditLog.queryCostSummary()` (new read methods added to port)
- **D-27:** If kore-storage absent, dashboard falls back to in-memory EventBus data only (degraded mode — no history)

#### Page Structure
- **D-28:** Single page at `/kore` with three HTMX fragments:
  - `/kore/fragments/active-agents` — polled every 2 seconds
  - `/kore/fragments/recent-runs` — polled every 5 seconds
  - `/kore/fragments/cost-summary` — polled every 10 seconds
- **D-29:** No drill-down pages in v0.1 — one page, three fragments
- **D-30:** HTMX polling via `hx-get` + `hx-trigger="every Ns"` — no WebSockets

#### Tech Stack
- **D-31:** Ktor 3.2+ embedded server with `ktor-server-htmx` module (per STACK.md)
- **D-32:** Server-rendered HTML via `kotlinx.html` DSL — no build step, no JavaScript framework
- **D-33:** Dashboard is a SEPARATE Ktor embedded server instance, not registered as Spring MVC routes (per Pitfall: Spring + Ktor coupling)
- **D-34:** Actuator endpoint at `/actuator/kore` is a separate Spring Actuator bean (not part of Ktor dashboard) — satisfies SPRN-04

### Claude's Discretion
- Exact YAML parser choice (kotlinx-serialization-yaml vs snakeyaml vs jackson-dataformat-yaml)
- `AuditLog` port method additions for dashboard queries (exact signatures)
- Dashboard HTML/CSS styling — minimal, readable, no framework required
- Whether `SkillRegistry` is a new kore-core port or lives fully in kore-skills
- Internal structure of `EventBusDashboardObserver` (single observer vs separate per section)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Spec
- `08-kore-runtime.md` — Full feature spec
- `.planning/PROJECT.md` — Core value, constraints

### Research
- `.planning/research/STACK.md` — Ktor 3.2 HTMX module, Spring Boot 4.0.5 auto-configuration, kotlinx-serialization versions
- `.planning/research/ARCHITECTURE.md` — Hexagonal module boundaries
- `.planning/research/PITFALLS.md` — Pitfall 12 (Spring Boot auto-configuration eager wiring)

### Phase 1 Code
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt` — Where skill activation hooks in
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentRunner.kt` — Spring bean return type
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt` — Dashboard data source
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt` — Add query methods here

### Phase 2 Code
- `kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/ObservableAgentRunner.kt` — Auto-wrap wiring
- `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt` — Read methods go here

### Phase 1 Context (inherited decisions)
- `.planning/phases/01-core-runtime/01-CONTEXT.md` — DSL decisions, hexagonal architecture

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `agent { }` DSL exists — Spring bean functions return its output directly
- `InProcessEventBus` with SharedFlow — dashboard subscribes like any other observer
- `PostgresAuditLogAdapter` — extended with read methods for dashboard queries
- `AgentLoop` already calls `AuditLog` at the right points — skill activation hooks in at the same layer

### Established Patterns
- Hexagonal: ports in kore-core, adapters in separate modules
- Pluggable via classpath detection in Spring auto-config
- Sealed classes for all domain results

### Integration Points
- kore-skills depends on kore-core (port interfaces)
- kore-spring depends on ALL kore modules (integration layer)
- kore-dashboard depends on kore-core (EventBus, AgentResult) + kore-storage (optional, for history)
- kore-spring auto-configures kore-dashboard if present on classpath

</code_context>

<specifics>
## Specific Ideas

- The README example must be: add `dev.unityinflow:kore-spring`, write one `@Bean` function, hit `/kore` in a browser. That's the full developer experience for Phase 3
- Skills should feel like dropping files into a directory — no restarts, no rebuilds (hot-reload is a nice-to-have but not required for v0.1)
- Dashboard should look clean without any CSS framework — readable server-rendered HTML

</specifics>

<deferred>
## Deferred Ideas

- Skill hot-reload (filesystem watcher) — defer to v0.2
- Dashboard drill-down pages (per-agent trace view) — defer to v0.2
- WebSocket streaming for active agents (vs HTMX polling) — polling is simpler, defer switch
- Real budget-breaker integration (Tool 05) — stays stub in v0.1

</deferred>

---

*Phase: 03-skills-spring-dashboard*
*Context gathered: 2026-04-12*
