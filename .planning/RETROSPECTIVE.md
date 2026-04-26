# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v0.0.1 — Initial MVP

**Shipped:** 2026-04-26
**Phases:** 4 | **Plans:** 21 | **Tasks:** 35 | **Commits:** 125
**Timeline:** 2026-04-10 → 2026-04-26 (16 days)
**Codebase:** ~22.9k Kotlin LOC across 11 modules

### What Was Built
- Kotlin coroutine-based ReAct agent loop with `agent { }` DSL, `AgentResult` sealed class, fallback chains (`claude() fallbackTo gpt()`), and per-backend semaphore concurrency
- Four LLM adapters behind one `LLMBackend` port: Claude, GPT (official SDKs) + Ollama, Gemini (LangChain4j transport) — zero provider type leakage
- Full MCP protocol support (client + server) using `io.modelcontextprotocol:kotlin-sdk:0.11.0` — stdio + SSE transports
- Skills engine: YAML loader (jackson-dataformat-yaml) + pattern-based activation matching, `SkillRegistry` port in kore-core with `NoOpSkillRegistry` default
- OpenTelemetry observability via decorator pattern (`ObservableAgentRunner` wraps `AgentLoop`) — kore-core stays zero-dep
- Micrometer metrics: 4 counters + gauge wired through `EventBusMetricsObserver`
- PostgreSQL audit log: Flyway V1 migrations, Exposed 1.0 R2DBC tables, JSONB type binding via custom `JsonbColumnType`/`JsonbTypeMapper` (priority 1.0)
- Spring Boot 4 auto-configuration starter (`kore-spring`) — drop-in dependency, `@Bean agent { }`, live HTMX dashboard
- Ktor 3.2 CIO embedded HTMX dashboard as Spring SmartLifecycle bean (`/kore/fragments/active-agents`, `/recent-runs`, `/cost-summary`)
- Pluggable event bus: Kotlin Flows default + opt-in Kafka and RabbitMQ adapters (triple-gated `@ConditionalOnClass(name=fqn-string)` + `@ConditionalOnProperty havingValue` + `@ConditionalOnMissingBean(EventBus::class)`)
- kore-test: `MockLLMBackend`, `MockToolProvider`, `SessionRecorder`, `SessionReplayer` for deterministic agent testing
- Maven Central publishing infrastructure: buildSrc `kore.publishing` precompiled convention plugin + `com.gradleup.nmcp` aggregation across 11 modules
- v0.0.1 release workflow + version bump + RC dry-run / final release checklist (`docs/RELEASE-CHECKLIST.md`)

### What Worked
- **Hexagonal-from-day-one paid off**: kore-core's runtime classpath is provably zero-dep (only kotlinx.coroutines + stdlib). Every adapter (OTel, Micrometer, Exposed, Spring, kotlinx.serialization, MCP SDK) layered on as `compileOnly` or in adapter modules
- **All four LLM backends in Phase 1, not deferred**: forced the `LLMBackend` port to be honestly provider-agnostic from the first commit (`String` JSON for tool args, `Flow<LLMChunk>` for streaming + non-streaming uniform)
- **kore-test built alongside kore-core**: `api()` declarations propagated MockK + Kotest + coroutines-test transitively to consumer test scopes — no second-class testing story
- **Decorator pattern for observability** (`ObservableAgentRunner`) instead of inheritance kept `AgentLoop` private and let kore-core stay OTel-free
- **Wave-0 bootstrap pattern in Phase 4**: consolidating shared-file edits (settings.gradle.kts, libs.versions.toml, module skeletons) into one early plan let later waves run in parallel without merge races
- **Compile-time gates for adapter wiring**: triple-gated Spring auto-config (`ConditionalOnClass(name=...)` + `ConditionalOnProperty havingValue` + `ConditionalOnMissingBean(EventBus::class)`) preserved opt-in semantics and let context tests assert at definition level (`hasBean(name)`) without ever instantiating broker-opening beans
- **Definition-level assertions in context tests**: avoided real socket opens (`KafkaEventBus(config, scope)` opens producer/consumer at construction), kept tests hermetic
- **kore-core public-factory wiring in `KoreAutoConfiguration`** (`claude()`, `gpt()`, `ollama()`, `gemini()`) instead of provider-SDK constructors — correct boundary, less plumbing

### What Was Inefficient
- **Version-pinning surprises**: planned versions for `anthropic-java` (2.20.0 → 0.1.0), `langchain4j` (1.0.1 → 0.26.1/0.36.1), `MCP SDK` (0.11.1 → 0.11.0) didn't exist on Maven Central — caused mid-phase corrections. Should research Maven Central directly before locking versions in research docs
- **Dashboard reflective bridge churn**: started with `Class.forName` reflection between kore-spring and kore-dashboard, replaced in plan 03-04 with direct constructor call once `kore-dashboard` was a `compileOnly` project dep. The intermediate state (3-arg constructor + JVM-erasure non-nullability) added planning load that the eventual direct call didn't need
- **Compile-only transitives bit twice**: kore-core's `compileOnly` `kotlinx-serialization-core` propagated as a class-init failure (`<clinit>` referencing `KSerializer`) into `kore-observability:test`, surfaced post-close in the deferred-items log. Same root cause as why kore-spring needed to redeclare `exposed-r2dbc` and `opentelemetry-api` as `compileOnly` — `compileOnly` project deps don't expose THEIR `compileOnly` transitives. Worth a checklist item for next milestone
- **Phase 02 verification gaps left `human_needed`**: 5 manual UAT items (OTel/Micrometer/Postgres+Flyway/commit-audit/kore-core OTel-import check) still pending at close. Should run `/gsd-verify-work` inline at phase boundaries rather than at close
- **`.planning/STATE.md` velocity tables drifted** (showed "Total plans completed: 10" at close even though 21 were done). Velocity tracker isn't being maintained — either remove or wire into `gsd-sdk` queries
- **Coroutine-test dispatcher gotcha**: `advanceUntilIdle` hangs on never-finishing collect loops; needed `backgroundScope + yield + runCurrent` for `EventBusMetricsObserverTest`. Cost a debug cycle — capture as test-pattern memory for next milestone

### Patterns Established
- **`compileOnly` for adapter SDK on adapter module + `compileOnly` mirror in kore-spring** — auto-configuration `@Bean` signatures need transitive types on compile classpath; `compileOnly` project deps don't propagate `compileOnly` transitives
- **`ConditionalOnClass(name=[fqn-string])` (string form)** + explicit `havingValue` + `ConditionalOnMissingBean(EventBus::class)` triple-gate is the canonical pattern for opt-in adapter wiring
- **Definition-level Spring context tests** (`assertThat(ctx).hasBean(beanName)` not `ctx.getBean()`) for any bean whose constructor would open external sockets
- **Top-level extension `toAdapterConfig()` in kore-spring** for mapping `KoreProperties.X` → adapter config types — keeps `data class` itself stdlib-only and lets compileOnly adapter type references live on extensions
- **`internal` (not `private`) on infix-call backing fields** so DSL combinators (e.g., `fallbackTo`) can copy state across instances
- **Companion `invoke` factory + internal primary constructor + `createForTest(deps, dispatcher)`** for any adapter that opens IO at construction (KafkaEventBus, RabbitMqEventBus) — deterministic `runTest` assertions without socket opens
- **`@SerialName` on every sealed-subclass at `@Serializable`** — explicit human-readable wire discriminator beats default FQN
- **`compileOnly(kotlinx-serialization-core)` on kore-core** + `testImplementation(libs.serialization.core)` on each downstream module that instantiates `@Serializable` types in tests (lesson from `kore-observability:test` post-close debug)

### Key Lessons
1. **Verify versions on Maven Central, not in research docs.** Three separate version corrections in Phase 1 alone wasted research-doc trust. Add a `gradle dependencyInsight`-equivalent verification step to the research workflow
2. **Run `/gsd-verify-work` at phase boundary, not at milestone close.** v0.0.1 closed with 5 `human_needed` UAT items in Phase 02 unaddressed. Verification at the boundary keeps the loop tight; deferring to close means humans face a backlog of cold tests
3. **`compileOnly` project deps need a transitive-aware companion convention.** Either always re-declare what auto-config `@Bean` signatures need, or write a Gradle convention plugin that auto-mirrors `compileOnly` transitives. The current "rediscover when CI fails" loop costs a debug cycle each time
4. **Velocity tables in STATE.md are vestigial.** They drifted to "10 plans completed" at 21/21. Either auto-populate via `gsd-sdk` post-plan or drop the section
5. **Reflective bridges between modules are usually a sign of a missing `compileOnly` project dep.** The Phase 3 `Class.forName` dance for `DashboardServer` got resolved by making `kore-dashboard` a `compileOnly` project dep; should have started there

### Cost Observations
- Model mix: ~70% opus (planning + heavy execution), ~30% sonnet (executor + light tasks)
- Sessions: not tracked — no cumulative session count for v0.0.1
- Notable: 21 plans / 35 tasks / 125 commits over 16 days = ~7.8 commits/day, ~1.3 plans/day. Strong cadence for a from-scratch multi-module Kotlin runtime; suggests the GSD plan/execute loop with parallel waves is the right tool for this codebase shape

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v0.0.1 | (untracked) | 4 | Established hexagonal-from-day-one + zero-dep core + test-alongside discipline |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v0.0.1 | 58+ unit/integration (HeroDemoTest + per-module suites) + Testcontainers Postgres | >80% target on kore-core | kore-core: 0 runtime deps beyond kotlinx.coroutines + stdlib |

### Top Lessons (Verified Across Milestones)

*To be cross-validated after v0.0.2.*

1. *(pending — single milestone so far)*
