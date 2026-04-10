# Roadmap: kore-runtime

## Overview

kore-runtime is built in four phases that follow module dependency order. Phase 1 establishes the executable core — agent loop, LLM backends, MCP protocol, budget enforcement, and testing infrastructure — all with zero external dependencies except kotlinx.coroutines. Phase 2 adds observability and durable storage. Phase 3 layers the skills engine, Spring Boot integration, and the HTMX dashboard on top of a proven runtime. Phase 4 completes the event bus with opt-in messaging adapters and publishes all modules to Maven Central.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Core Runtime** - Agent loop, LLM backends (all 4), MCP client+server, budget enforcement, kore-test
- [ ] **Phase 2: Observability & Storage** - OpenTelemetry spans, Micrometer metrics, PostgreSQL audit log via Flyway
- [ ] **Phase 3: Skills, Spring & Dashboard** - Skills engine, Spring Boot starter, HTMX admin dashboard
- [ ] **Phase 4: Event Bus & Publishing** - EventBus port, Kotlin Flows default, Kafka/RabbitMQ adapters, Maven Central

## Phase Details

### Phase 1: Core Runtime
**Goal**: A developer can run an agent that calls LLMs, uses MCP tools, enforces a token budget, and is fully testable without network access
**Depends on**: Nothing (first phase)
**Requirements**: CORE-01, CORE-02, CORE-03, CORE-04, CORE-05, CORE-06, CORE-07, LLM-01, LLM-02, LLM-03, LLM-04, LLM-05, MCP-01, MCP-02, MCP-03, MCP-04, MCP-05, MCP-06, BUDG-01, BUDG-02, BUDG-03, BUDG-04, TEST-01, TEST-02, TEST-03, TEST-04
**Success Criteria** (what must be TRUE):
  1. An agent defined with `agent { model = claude(); tools = mcp("server") }` executes the full loop (task -> LLM -> tool use -> result) using coroutines
  2. AgentResult returns BudgetExceeded when the configured token limit is reached before the loop completes
  3. An agent configured with `claude() fallbackTo gpt()` transparently retries via the fallback backend on LLM error
  4. A test using MockLLMBackend produces deterministic, reproducible agent behavior without any network calls
  5. An MCP client connects to an MCP server over both stdio and SSE, calls tools, reads resources, and uses prompt templates
**Plans**: 7 plans

Plans:
- [x] 01-01-PLAN.md — Gradle multi-module scaffold (settings, build, libs.versions.toml, CI workflow)
- [x] 01-02-PLAN.md — kore-core domain types (AgentResult, LLMChunk) and port interfaces (LLMBackend, BudgetEnforcer, ToolProvider, EventBus, AuditLog)
- [x] 01-03-PLAN.md — AgentLoop, Kotlin DSL (agent { }), InMemoryBudgetEnforcer, InProcessEventBus stubs
- [ ] 01-04-PLAN.md — kore-test: MockLLMBackend, MockToolProvider, SessionRecorder, SessionReplayer
- [ ] 01-05-PLAN.md — kore-llm: ClaudeBackend, OpenAiBackend, OllamaBackend, GeminiBackend, DSL factory functions
- [ ] 01-06-PLAN.md — kore-mcp: McpClientAdapter (tools+resources+prompts), McpServerAdapter
- [ ] 01-07-PLAN.md — HeroDemoTest integration, README/CONTRIBUTING/LICENSE, Phase 1 completion gate

### Phase 2: Observability & Storage
**Goal**: Every agent run, LLM call, and tool invocation is traced with OpenTelemetry and durably recorded in PostgreSQL
**Depends on**: Phase 1
**Requirements**: OBSV-01, OBSV-02, OBSV-03, OBSV-04, OBSV-05, STOR-01, STOR-02, STOR-03, STOR-04
**Success Criteria** (what must be TRUE):
  1. A connected OpenTelemetry collector receives spans for every LLM call, tool use, and skill activation with correct attributes (model, tokens, duration)
  2. OTel trace context is correctly propagated across coroutine suspension boundaries (OpenTelemetryContextElement)
  3. Micrometer metrics counters for agent_runs_total, llm_calls_total, tokens_used_total, and errors_total are visible in a Micrometer-compatible registry
  4. After an agent run, the agent_runs, llm_calls, and tool_calls rows are present in PostgreSQL with correct foreign-key linkage
  5. Running `./gradlew flywayMigrate` on a clean database creates all required schema tables without errors
**Plans**: TBD

### Phase 3: Skills, Spring & Dashboard
**Goal**: An existing Spring Boot application gains a fully working agent with skills, autoconfiguration, and a live HTMX dashboard by adding one Gradle dependency
**Depends on**: Phase 2
**Requirements**: SKIL-01, SKIL-02, SKIL-03, SKIL-04, SPRN-01, SPRN-02, SPRN-03, SPRN-04, DASH-01, DASH-02, DASH-03, DASH-04
**Success Criteria** (what must be TRUE):
  1. A YAML skill file in the configured skills directory is automatically loaded and its activation pattern fires when matching task content is processed
  2. Adding `dev.unityinflow:kore-spring` to build.gradle.kts and creating an `agent {}` block in application code produces a running agent — no additional configuration required
  3. The HTMX dashboard at `/kore` shows currently active agents, a list of recent runs with results, and a per-agent token cost summary, all without a frontend build step
  4. Spring Actuator at `/actuator/kore` reports agent health and echoes live metrics
**Plans**: TBD
**UI hint**: yes

### Phase 4: Event Bus & Publishing
**Goal**: The event bus abstraction is complete with a production-ready default and opt-in messaging adapters, and all kore modules are published to Maven Central
**Depends on**: Phase 3
**Requirements**: EVNT-01, EVNT-02, EVNT-03, EVNT-04
**Success Criteria** (what must be TRUE):
  1. Agent lifecycle events flow through the Kotlin Flows SharedFlow bus by default with DROP_OLDEST backpressure, requiring zero configuration from the developer
  2. Adding the `kore-kafka` module and `kore.event-bus=kafka` in application.yml switches the event bus to Kafka without changing any agent code
  3. Adding the `kore-rabbitmq` module and `kore.event-bus=rabbitmq` in application.yml switches the event bus to RabbitMQ without changing any agent code
  4. All kore modules (kore-core, kore-mcp, kore-skills, kore-observability, kore-storage, kore-spring, kore-test) are resolvable from Maven Central as `dev.unityinflow:kore-*`
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Core Runtime | 2/7 | In Progress|  |
| 2. Observability & Storage | 0/TBD | Not started | - |
| 3. Skills, Spring & Dashboard | 0/TBD | Not started | - |
| 4. Event Bus & Publishing | 0/TBD | Not started | - |
