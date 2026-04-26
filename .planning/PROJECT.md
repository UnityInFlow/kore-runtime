# kore-runtime

## What This Is

A production-grade Kotlin agent runtime for the JVM. kore lets developers define, run, and observe AI agents using a Kotlin DSL, with coroutine-based execution, MCP protocol support, multi-LLM backends (Claude/GPT/Ollama/Gemini), Spring Boot auto-configuration, OpenTelemetry observability, PostgreSQL audit logging, an HTMX dashboard, and a pluggable event bus (Kotlin Flows by default; Kafka/RabbitMQ opt-in). It fills the gap between LangChain4j's LLM call abstraction and what enterprises actually need to run agents in production.

## Core Value

The agent loop must work — task intake, LLM call, tool use, result handling, loop. A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control. Everything else is additive.

**v0.0.1 validated this:** the integration test (`HeroDemoTest`) and the README hero demo both exercise the full path: one Gradle dependency, one `@Bean agent { }`, live HTMX dashboard, OTel spans, Postgres audit rows.

## Current State

**Shipped:** v0.0.1 Initial MVP (2026-04-26) — 4 phases, 21 plans, ~22.9k Kotlin LOC across 11 modules, 125 commits over 16 days.

**On Maven Central** (pending release pipeline run): `dev.unityinflow:kore-{core,mcp,llm,skills,observability,storage,spring,dashboard,kafka,rabbitmq,test}:0.0.1`.

**Stack:** Kotlin 2.x · Gradle 9.4.1 · JVM 21 · Spring Boot 4 · Ktor 3.2 · Exposed 1.0 (R2DBC) · PostgreSQL · Flyway 12 · OpenTelemetry · Micrometer · MCP Kotlin SDK 0.11.0 · LangChain4j (Ollama/Gemini transport) · Anthropic Java SDK · OpenAI Java SDK · kotlinx.serialization (compile-only on kore-core) · MockK · Kotest assertions · JUnit 5 · Testcontainers · ktlint via kotlinter · GraalVM-aware buildSrc + nmcp Maven Central publishing.

**Known deferred items at close** (see STATE.md `## Deferred Items`):
- Phase 01 + Phase 02 verification gaps marked `human_needed` — awaiting manual UAT pass
- `kore-observability:test` had 12 pre-existing `NoClassDefFoundError`s on `main` resolved post-close (kotlinx-serialization-core compileOnly transitive — see `.planning/debug/knowledge-base.md`)

## Requirements

### Validated

- ✓ Kotlin coroutine agent loop: task intake → LLM call → tool use → result → loop — v0.0.1 (Phase 1)
- ✓ Kotlin DSL for agent definition (`agent("name") { model = claude(); tools = mcp("github") }`) — v0.0.1 (Phase 1)
- ✓ Coroutine per agent with semaphore-based LLM rate limiting — v0.0.1 (Phase 1)
- ✓ LLM backends: Claude, GPT, Ollama, Gemini (all four from day one) — v0.0.1 (Phase 1)
- ✓ LLM fallback chains: retry with exponential backoff, fallback to alternate backend (`claude() fallbackTo gpt()`) — v0.0.1 (Phase 1)
- ✓ AgentResult sealed class: Success | BudgetExceeded | ToolError | LLMError — v0.0.1 (Phase 1)
- ✓ MCP protocol client: tools, resources, prompts over stdio and SSE — v0.0.1 (Phase 1)
- ✓ MCP protocol server: expose kore agents as MCP tools to external clients — v0.0.1 (Phase 1)
- ✓ Skills engine: YAML skill definitions with pattern-based activation context matching — v0.0.1 (Phase 3)
- ✓ BudgetEnforcer port interface with in-memory stub (real budget-breaker adapter later) — v0.0.1 (Phase 1)
- ✓ OpenTelemetry span on every LLM call, tool use, skill activation — v0.0.1 (Phase 2 + 3)
- ✓ Micrometer metrics registration for agent runs, token usage, error rates — v0.0.1 (Phase 2)
- ✓ PostgreSQL audit log via Flyway migrations (agent_runs, llm_calls, tool_calls) — v0.0.1 (Phase 2)
- ✓ Spring Boot starter: auto-configuration from application.yml — v0.0.1 (Phase 3)
- ✓ Pluggable event bus: Kotlin Flows (default), Kafka (opt-in), RabbitMQ (opt-in) — v0.0.1 (Phase 4)
- ✓ HTMX dashboard: active agents, recent runs, token cost summary — v0.0.1 (Phase 3)
- ✓ kore-test module: MockLLMBackend + session recording/replay for deterministic agent testing — v0.0.1 (Phase 1)

### Active

(Define for next milestone via `/gsd-new-milestone`. Carried-over candidates:)

- [ ] Hierarchical agents: parent agents spawn child agents with structured concurrency (deferred from v0.0.1 — original scope item, not exercised in initial loop)
- [ ] Real budget-breaker adapter (replaces InMemoryBudgetEnforcer stub once Tool 05 ships)
- [ ] Resolve Phase 01 + Phase 02 `human_needed` verification UAT items
- [ ] Skill-activation OTel span (OBSV-03) — intentionally deferred from Phase 2; needs wiring against `SkillActivated` event from Phase 3 implementation

### Out of Scope

- Kafka/RabbitMQ as hard dependencies — confirmed by v0.0.1: Kotlin Flows is the default, adapters are opt-in via `kore.event-bus.type`
- Frontend SPA for dashboard — HTMX + kotlinx.html proved sufficient (no JS build step shipped)
- Python/TypeScript SDKs — JVM only for v0.0.x line
- Agent marketplace / registry — agents remain code, not hosted
- Mobile SDKs — server-side runtime only

## Context

Tool 08 in the UnityInFlow AI Agent Tooling Ecosystem (20 open-source tools). v0.0.1 is the initial MVP shipping all 11 modules. Depends on budget-breaker (Tool 05) for real token-budget enforcement, currently satisfied by InMemoryBudgetEnforcer per the hexagonal port boundary.

The JVM ecosystem has no production-grade agent runtime. LangChain4j provides LLM call abstraction but not: reactive execution, skills system, governance, event bus, or observability. v0.0.1 fills that gap end-to-end.

Three target personas:
- **Backend developers** — adding AI features to existing Spring Boot apps (kore-core + kore-spring)
- **Platform engineers** — running agents at scale with observability and governance (kore-observability + kore-storage)
- **AI engineers** — building agent-first products, coming from Python/LangChain (kore-core + kore-mcp + kore-skills + kore-test)

## Constraints

- **Language**: Kotlin 2.0+, JVM target 21
- **Build**: Gradle (Kotlin DSL only — never Groovy)
- **Architecture**: Hexagonal — LLM backends, event bus, storage, budget enforcement are ports/adapters
- **Async**: Coroutines everywhere — never Thread.sleep() or raw threads
- **Immutability**: `val` only, no `var`. Immutable data classes for value objects
- **Error handling**: Sealed classes (AgentResult) instead of exceptions for expected failures
- **Testing**: JUnit 5 + Kotest matchers. Coverage >80% on core logic
- **Format**: ktlint before every commit
- **Distribution**: Maven Central via Sonatype. Group: `dev.unityinflow`
- **CI**: Self-hosted runners (arc-runner-unityinflow). Never ubuntu-latest
- **Dashboard**: HTMX + Ktor embedded server. No frontend build step
- **Event bus**: Kotlin Flows default. Kafka/RabbitMQ opt-in only
- **Secrets**: Never committed. All credentials via environment variables
- **Docs**: KDoc on all public APIs
- **Zero-dep core** *(validated v0.0.1)*: kore-core's runtime classpath has only kotlinx.coroutines + stdlib. All adapter dependencies (OTel, Micrometer, Exposed, Spring, kotlinx.serialization, MCP SDK) are `compileOnly` on kore-core or live in adapter modules

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Kotlin DSL for agent API | Idiomatic, concise, discoverable. README examples must feel native to Kotlin developers. Annotation-based support can layer on later | ✓ Good — `agent { }` DSL with `@KoreDsl` DslMarker shipped, hero demo reads naturally |
| All 4 LLM backends from day one | Proves LLMBackend port interface is honest abstraction, not Claude-shaped. Credibility with multi-LLM users | ✓ Good — Claude/GPT (official SDKs) + Ollama/Gemini (LangChain4j transport) all behind one `LLMBackend` port; zero provider-type leakage |
| Full MCP (client + server) | Positions kore as THE JVM MCP implementation. Agents can both call and be called via MCP | ✓ Good — kotlin-sdk 0.11.0; client (stdio + SSE) + server adapter both shipped, `invokeAgent()` extracted public for testability |
| Interface + stub for budget-breaker | Hexagonal architecture allows building the port now, real adapter later. Avoids blocking on Tool 05 | ✓ Good — InMemoryBudgetEnforcer with `ConcurrentHashMap.merge` for atomic accumulation; swap-in path clean |
| Pattern-based skill activation | Skills declare activation patterns in YAML. Runtime matches automatically. Similar to Superpowers auto-activation | ✓ Good — `jackson-dataformat-yaml` (kaml archived Nov 2025); SkillRegistry port in kore-core, NoOpSkillRegistry default keeps zero-dep core |
| Coroutine per agent + semaphore | Lightweight (KB per agent), structured concurrency for cancellation, semaphore configurable per LLM backend | ✓ Good — per-backend semaphore in adapters, AgentLoop history mutated in place per iteration |
| Retry + fallback for LLM resilience | Enterprise-grade: exponential backoff + fallback chain (e.g., claude() fallbackTo gpt() fallbackTo ollama()) | ✓ Good — `ResilientLLMBackend` with `internal retryPolicy` + `fallbackTo` infix |
| Hierarchical multi-agent | Parent/child agents via structured concurrency. Cancelling parent cancels children. Enables supervisor/worker patterns | ⚠️ Revisit — original scope item; the agent loop supports it via coroutine scoping but no explicit parent/child API shipped. Address in next milestone |
| kore-test with Mock + record/replay | Strong differentiator — LangChain4j has no testing story. MockLLMBackend for unit tests, recording for CI replay | ✓ Good — SessionRecorder + SessionReplayer; serializable chunk types kept in one file to preserve zero-dep contract on kore-core |
| Minimal HTMX dashboard in v0.1 | Active agents, recent runs, cost summary. Good for demos and README screenshots. No frontend build step | ✓ Good — Ktor 3.2 CIO embedded as Spring SmartLifecycle bean; manual `hx-*` attrs (skipped experimental ktor-server-htmx) |
| OTel kept entirely out of kore-core (D-10) | Observability is a pure adapter; consumers supply OTel via Spring Boot 4 BOM | ✓ Good — kore-observability decorator pattern (`ObservableAgentRunner` wraps `AgentLoop`); `compileOnly` for otel-api/micrometer-core |
| compileOnly kotlinx.serialization on kore-core | `@Serializable` annotations on AgentEvent/AgentResult without polluting runtime classpath | ✓ Good — required `testImplementation` propagation in downstream modules (caught + fixed via `kore-observability:test` post-close) |
| Spring Boot 4 + Exposed 1.0 (R2DBC) | First-class Kotlin support in Boot 4; Exposed 1.0 GA with R2DBC + JSONB | ✓ Good — required custom `JsonbColumnType`/`JsonbTypeMapper` (priority 1.0); `ConnectionFactoryOptions.parse(url)` needed for dialect detection |
| `@ConditionalOnClass(name=[fqn-string])` for adapter wiring | String-form FQN check + `@ConditionalOnMissingBean(EventBus::class)` + explicit `havingValue` triple-gate preserves opt-in semantics for Kafka/RabbitMQ | ✓ Good — context tests assert at definition level (`hasBean(name)`) — never instantiate beans that would open broker sockets |
| nmcp Maven Central publisher (no version alias) | New Sonatype Central portal API; alias() conflicts with Gradle's classpath-version guard | ✓ Good — buildSrc convention plugin pins nmcp; root applies plugin without version |

## Module Structure

```
kore/
├── kore-core/          <- agent loop, DSL, interfaces, sealed classes, concurrency (zero-dep core)
├── kore-llm/           <- ClaudeBackend, OpenAiBackend, OllamaBackend, GeminiBackend (kore-llm.* DSL factories)
├── kore-mcp/           <- MCP client (tools + resources + prompts) + MCP server adapter
├── kore-skills/        <- YAML skill loader + PatternMatcher + SkillRegistryAdapter
├── kore-observability/ <- KoreTracer, ObservableAgentRunner, EventBusSpanObserver, KoreMetrics
├── kore-storage/       <- Flyway V1 migrations + Exposed R2DBC tables + PostgresAuditLogAdapter
├── kore-dashboard/     <- Ktor 3.2 CIO + kotlinx.html HTMX fragments (SmartLifecycle bean)
├── kore-kafka/         <- KafkaEventBus (opt-in via kore.event-bus.type=kafka)
├── kore-rabbitmq/      <- RabbitMqEventBus (opt-in via kore.event-bus.type=rabbitmq)
├── kore-spring/        <- Spring Boot auto-configuration starter (KoreAutoConfiguration)
└── kore-test/          <- MockLLMBackend, MockToolProvider, SessionRecorder, SessionReplayer
```

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-26 after v0.0.1 milestone*
