---
gsd_state_version: 1.0
milestone: v0.0.2
milestone_name: Hardening & Hierarchy
status: verifying
stopped_at: Phase 7 context gathered
last_updated: "2026-06-21T16:10:01.354Z"
last_activity: 2026-06-21
progress:
  total_phases: 3
  completed_phases: 2
  total_plans: 6
  completed_plans: 6
  percent: 67
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-12)

**Core value:** A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control.
**Current focus:** Phase 06 — real-budget-enforcement

## Current Position

Phase: 7
Plan: Not started
Status: Phase complete — ready for verification
Last activity: 2026-06-21

Progress: [███░░░░░░░] 33% (1 of 3 milestone phases complete)

**Milestone phase structure:**

- Phase 5: CI Baseline & Skill Observability (CI-01, CI-02, OBSV-03, OBSV-04)
- Phase 6: Real Budget Enforcement (BUDG-05, BUDG-06, BUDG-07) — independent of Phase 5
- Phase 7: Hierarchical Agents (HIER-01..04) — must follow Phase 5 (shared AgentLoop.kt)

## Performance Metrics

**Velocity:**

- Total plans completed: 27 (v0.0.1)
- Average duration: -
- Total execution time: -

**By Phase (v0.0.1):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 7 | - | - |
| 02 | 3 | - | - |
| 03 | 5 | - | - |
| 04 | 6 | - | - |
| 5 | 4 | - | - |
| 06 | 2 | - | - |

*Updated after each plan completion*
| Phase 01-core-runtime P01 | 4 | 2 tasks | 20 files |
| Phase 01-core-runtime P02 | 4min | 2 tasks | 19 files |
| Phase 01-core-runtime P03 | 4min | 2 tasks | 11 files |
| Phase 01-core-runtime P04 | 2min | 2 tasks | 5 files |
| Phase 01-core-runtime P05 | 15min | 2 tasks | 11 files |
| Phase 01-core-runtime P06 | 10min | 2 tasks | 7 files |
| Phase 01-core-runtime P07 | 5min | 3 tasks | 5 files |
| Phase 02-observability-storage P01 | 3min | 2 tasks | 8 files |
| Phase 02-observability-storage P02 | 45 | 2 tasks | 12 files |
| Phase 02-observability-storage P03 | 58min | 2 tasks | 4 files |
| Phase 03-skills-spring-dashboard P01 | 30min | 2 tasks | 19 files |
| Phase 03-skills-spring-dashboard P02 | 6min | 2 tasks | 7 files |
| Phase 03-skills-spring-dashboard P03 | 8min | 2 tasks | 11 files |
| Phase 03-skills-spring-dashboard P04 | 6min | 1 tasks | 6 files |
| Phase 03-skills-spring-dashboard P05 | 18min | 2 tasks | 10 files |
| Phase 04-event-bus-publishing P01 | 12min | 3 tasks | 11 files |
| Phase 04-event-bus-publishing P02 | 8min | 2 tasks | 8 files |
| Phase 04-event-bus-publishing P03 | 7min | 2 tasks | 6 files |
| Phase 04-event-bus-publishing P04 | 12min | 2 tasks | 6 files |
| Phase 04-event-bus-publishing P05 | 5min | 2 tasks | 14 files |
| Phase 05-ci-baseline-skill-observability P01 | 2min | 2 tasks | 2 files |
| Phase 05-ci-baseline-skill-observability P02 | 12min | 2 tasks | 7 files |
| Phase 05 P02 | 12min | 2 tasks | 7 files |
| Phase 05-ci-baseline-skill-observability P04 | 8min | 1 tasks | 2 files |
| Phase 05 P04 | 2min | 1 tasks | 2 files |
| Phase 05-ci-baseline-skill-observability P03 | 6min | 2 tasks | 5 files |
| Phase 05 P03 | 8min | 2 tasks | 6 files |
| Phase 06-real-budget-enforcement P01 | 8min | 2 tasks | 4 files |
| Phase 06-real-budget-enforcement P02 | 2min | 2 tasks | 4 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- **[v0.0.2 roadmap]**: 3 phases (5-7) per research SUMMARY.md — CI/OBSV first (zero risk, unblocks CI correctness), budget adapter second (isolated new kore-budget module), hierarchy last (largest kore-core surface; must follow Phase 5 because both modify AgentLoop.kt)
- **[v0.0.2 roadmap]**: kore-budget gates auto-config on class presence of budget-breaker core library only — `budget-breaker-spring-boot-starter` not yet published
- **[v0.0.2 roadmap]**: Spawn model for hierarchical agents (child-as-tool-call), not handoff — handoff breaks the ReAct loop and severs the coroutine scope chain
- **[v0.0.2 roadmap]**: All new `AgentLoop`/`AgentTask` constructor parameters must have default values to preserve binary compatibility
- kore-core must have zero external dependencies except kotlinx.coroutines + stdlib
- OTel context propagation (OpenTelemetryContextElement) must be in kore-core from day one
- LLMBackend interface must be designed against all 4 providers simultaneously
- kore-test built alongside kore-core (Phase 1), not deferred to later
- kore-spring is last integration layer — depends on all adapters (Phase 3)
- [Phase 01-core-runtime]: kore-llm created as separate module keeping kore-core free of LLM SDK dependencies (D-15)
- [Phase 01-core-runtime]: kore-test uses api() declarations so MockK, Kotest, coroutines-test propagate transitively to consumer test scopes
- [Phase 01-core-runtime]: kotlinter (org.jmailen.kotlinter) chosen for ktlint integration — simpler Gradle plugin, actively maintained
- [Phase 01-core-runtime]: LLMBackend.call() returns Flow<LLMChunk> (not suspend) so streaming and non-streaming backends satisfy one interface without overloading
- [Phase 01-core-runtime]: LLMChunk.ToolCall.arguments is String (JSON) not Map<String,Any> — provider-agnostic per D-07 and Pitfall 5
- [Phase 01-core-runtime]: BudgetEnforcer.checkBudget() returns Boolean (continue=true) rather than throwing — consistent with AgentResult no-throw contract
- [Phase 01-core-runtime]: AgentLoop history is MutableList mutated in-place each iteration — not re-created
- [Phase 01-core-runtime]: InMemoryBudgetEnforcer uses ConcurrentHashMap.merge for atomic token accumulation (thread-safe)
- [Phase 01-core-runtime]: ResilientLLMBackend.retryPolicy is internal (not private) so fallbackTo infix can copy it when chaining
- [Phase 01-core-runtime]: SessionRecorder.kt hosts SessionReplayer — shared SerializableChunk types kept in one file, avoids adding @Serializable to kore-core domain types (preserves D-15 zero-dep constraint)
- [Phase 01-core-runtime]: anthropic-java version corrected 2.20.0→0.1.0 (planned version doesn't exist on Maven Central; okhttp artifact is separate)
- [Phase 01-core-runtime]: langchain4j 1.0.1→0.26.1/0.36.1 (planned version doesn't exist; ollama and gemini on separate version trains, Gradle resolves langchain4j-core to 0.36.1 for both)
- [Phase 01-core-runtime]: ChatLanguageModel injected as constructor param to OllamaBackend and GeminiBackend — enables MockK mocking without network, no concrete LangChain4j model types in signatures
- [Phase 01-core-runtime]: McpServerAdapter.invokeAgent() extracted as public suspend fun for testability without transport
- [Phase 01-core-runtime]: MCP SDK version resolved as 0.11.0 (plan specified 0.11.1); APIs identical
- [Phase 01-core-runtime]: HeroDemoTest comment enforces README/test sync — test must be updated first if README hero demo changes (T-07-01 mitigation)
- [Phase 01-core-runtime]: kore-core testImplementation depends on kore-test — runtimeClasspath stays clean (zero non-coroutines external deps)
- [Phase 02-observability-storage]: OTel kept out of kore-core entirely (D-10): all observability lives in kore-observability as a pure adapter
- [Phase 02-observability-storage]: Decorator pattern for ObservableAgentRunner wrapping AgentLoop (AgentRunner has private scope, not extensible)
- [Phase 02-observability-storage]: compileOnly for otel-api and micrometer-core in kore-observability: consumers supply versions via Spring Boot 4 BOM
- [Phase 02-observability-storage]: org.postgresql:r2dbc-postgresql (not io.r2dbc) for 1.0.x JSONB support; custom JsonbColumnType+JsonbTypeMapper with priority 1.0 for Exposed JSONB binding
- [Phase 02-observability-storage]: ConnectionFactoryOptions.parse(url) required for R2dbcDatabase dialect detection; plain URL string loses dialect metadata
- [Phase 02-observability-storage]: UUIDTable from org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable — .java. sub-package in Exposed 1.0; suspendTransaction from .r2dbc.transactions (not .r2dbc directly)
- [Phase 02-observability-storage]: backgroundScope + yield + runCurrent for infinite-flow coroutine testing in EventBusMetricsObserverTest (advanceUntilIdle hangs on never-finishing collect loops)
- [Phase 02-observability-storage]: agentNameResolver lambda injected into EventBusMetricsObserver — Phase 3 kore-spring wires in name registry without changing observer
- [Phase 02-observability-storage]: model/backend tags default to 'unknown' in EventBusMetricsObserver — LLMCallCompleted lacks model info; Phase 3 enriches via OTel span context
- [Phase 03-skills-spring-dashboard]: SkillRegistry port lives in kore-core with NoOpSkillRegistry default (D-09); kore-skills is a pure adapter, kore-core stays zero runtime-dep
- [Phase 03-skills-spring-dashboard]: jackson-dataformat-yaml chosen as YAML parser (kaml archived Nov 2025); version managed by Spring Boot 4 BOM via io.spring.dependency-management plugin
- [Phase 03-skills-spring-dashboard]: AgentLoop.tracer is nullable (Tracer? = null); opentelemetry-api is compileOnly in kore-core so runtime classpath stays free of OTel
- [Phase 03-skills-spring-dashboard]: AgentBuilder uses Array<SkillRegistry>(1) val-cell backing store instead of var to satisfy CLAUDE.md no-var rule
- [Phase 03-skills-spring-dashboard]: PostgresAuditLogAdapter dashboard queries use .select(columns) projections instead of selectAll() to avoid the JsonbTypeMapper column-index bug on joined queries with jsonb columns
- [Phase 03-skills-spring-dashboard]: queryCostSummary folds join results in Kotlin instead of SQL GROUP BY — dialect-agnostic and correct for dashboard scale
- [Phase 03-skills-spring-dashboard]: kore-spring uses kore-llm public factory functions (claude/gpt/ollama/gemini) in @Bean methods rather than calling provider SDK constructors directly — the underlying clients (AnthropicClient, OpenAIClient, ChatLanguageModel) are constructor-required and the factory functions already encapsulate client wiring from an api key
- [Phase 03-skills-spring-dashboard]: OllamaLlmAutoConfiguration gated on explicit kore.llm.ollama.enabled=true (defaults false) — adding kore-llm to classpath must NOT eagerly construct an OllamaChatModel because langchain4j-ollama 0.26.1 vs langchain4j-google-ai-gemini 0.36.1 resolve to incompatible langchain4j-core versions causing NoSuchMethodError
- [Phase 03-skills-spring-dashboard]: kore-spring redeclares exposed-r2dbc and opentelemetry-api as compileOnly transitively — compileOnly project deps don't expose THEIR compileOnly transitives, so KoreAutoConfiguration's direct @Bean signatures need both symbols on the compile classpath
- [Phase 03-skills-spring-dashboard]: DashboardAutoConfiguration uses Class.forName reflection bridge until plan 03-03 creates kore-dashboard module; @ConditionalOnClass(name=["...DashboardServer"]) ensures reflection only runs at runtime when dashboard is present
- [Phase 03-skills-spring-dashboard]: EventBusDashboardObserver takes injected CoroutineScope (mirrors EventBusMetricsObserver pattern); tests use runTest backgroundScope, DashboardServer uses internal supervisor scope — no internal AtomicReference<Job?>
- [Phase 03-skills-spring-dashboard]: DashboardServer 3-arg constructor (EventBus, AuditLog, DashboardProperties) takes non-nullable AuditLog because kore-spring's reflective bridge uses getConstructor(...) — JVM erasure cannot resolve nullable parameter types; convenience constructor with InertAuditLog sentinel handles the explicit-null degraded path
- [Phase 03-skills-spring-dashboard]: configureDashboardRoutes is a Route extension (not Routing) — Ktor 3.2 routing { } block lambda receiver is Route.() -> Unit; Routing extends Route so Route is the more general (and only) correct binding
- [Phase 03-skills-spring-dashboard]: ktor-server-htmx plugin intentionally not used; HTMX attributes emitted manually via kotlinx.html attributes["hx-get"] = ... to avoid @OptIn(ExperimentalKtorApi::class)
- [Phase 03-skills-spring-dashboard]: Replaced reflective DashboardServer bridge in KoreAutoConfiguration with direct constructor call once kore-dashboard became compileOnly project dep; KoreDashboardPropertiesAdapter (private nested class in kore-spring) bridges KoreProperties.DashboardProperties to DashboardServer.DashboardProperties without leaking kore-spring into kore-dashboard
- [Phase 03-skills-spring-dashboard]: Removed @ConditionalOnProperty(enabled=true) from DashboardAutoConfiguration; bean creation gated only by classpath, engine startup gated by isAutoStartup() at SmartLifecycle level — separates 'create the bean' from 'bind the port' so tests can inject DashboardServer without binding 8090
- [Phase 03-skills-spring-dashboard]: Gap closure 03-05: AuditLog.isPersistent interface default + DashboardServer AtomicReference-backed scope swap closed HI-01 + HI-02 with 11 new tests across kore-dashboard (8) + kore-spring (3); zero new deps in kore-spring build
- [Phase 04-event-bus-publishing]: compileOnly kotlinx.serialization on kore-core (both -core and -json) keeps runtime classpath zero-external-dep while enabling @Serializable + @JsonClassDiscriminator annotations
- [Phase 04-event-bus-publishing]: @SerialName on every AgentEvent/AgentResult subclass — default kotlinx.serialization discriminator uses FQN, explicit SerialName gives human-readable wire format in broker admin UIs
- [Phase 04-event-bus-publishing]: File-scope DESERIALIZED_CAUSE sentinel instead of private companion object — Kotlin 2.3.0 IR backend bug in SyntheticAccessorLowering when data class default value references a private companion member
- [Phase 04-event-bus-publishing]: Wave-0 bootstrap: consolidate all shared-file edits (settings.gradle.kts + libs.versions.toml + module skeletons) into plan 04-01 so later waves run on disjoint file sets without merge races
- [Phase 04-event-bus-publishing]: kore-kafka uses internal primary constructor + companion invoke factory (no UnsupportedOperationException hack); createForTest injects mocked producer/consumer and a TestDispatcher for deterministic runTest assertions
- [Phase 04-event-bus-publishing]: Kafka broadcast semantics: consumer group ID = prefix-hostname-pid (InetAddress + ProcessHandle), Jackson excluded from kafka-clients to avoid Spring Boot BOM transitive skew (Pitfall 12)
- [Phase 04-event-bus-publishing]: RabbitMqEventBus uses lazy Connection + lazy publishChannel (Pitfall 7 defense) — construction never opens a broker socket
- [Phase 04-event-bus-publishing]: RabbitMqEventBus mirrors kore-kafka shape: internal primary ctor + companion invoke factory + createForTest(factory, ioDispatcher) for deterministic runTest assertions
- [Phase 04-event-bus-publishing]: basicNack(requeue=false) on decode failure prevents poison-message redelivery loops; explicit AMQP.Queue.DeclareOk mock avoids MockK chained-stub gotcha
- [Phase 04-event-bus-publishing]: KafkaEventBusAutoConfiguration + RabbitMqEventBusAutoConfiguration use @ConditionalOnClass(name=[fqn-string]) string form (Pitfall 2) + explicit havingValue='kafka'/'rabbitmq' (Pitfall 8) + @ConditionalOnMissingBean(EventBus::class) — triple gate preserves in-process default and user-override precedence
- [Phase 04-event-bus-publishing]: Spring context tests use assertThat(ctx).hasBean(beanName) definition-level assertion instead of ctx.getBean() — KafkaEventBus(config, scope) opens real KafkaProducer/KafkaConsumer at construction, so factory invocation would open TCP socket to localhost:9092 and crash context refresh
- [Phase 04-event-bus-publishing]: kore-spring compileOnly(kore-kafka) + compileOnly(kore-rabbitmq) preserves opt-in semantics (Pitfall 3) — consumers must explicitly depend on adapter modules, kore-spring never transitively pulls them
- [Phase 04-event-bus-publishing]: toAdapterConfig() as top-level extension functions (not member functions) on KoreProperties.KafkaProperties/RabbitMqProperties — data classes themselves reference only stdlib, mapping extensions handle compileOnly kore-kafka/kore-rabbitmq type references
- [Phase 04-event-bus-publishing]: buildSrc/kore.publishing precompiled script convention plugin applies java-library + maven-publish + signing + com.gradleup.nmcp with full POM template; java-library required at convention-plugin level because maven-publish alone doesn't expose java{} extension to precompiled scripts
- [Phase 04-event-bus-publishing]: Root applies id("com.gradleup.nmcp.aggregation") WITHOUT version (not via alias) — buildSrc already puts nmcp marker on classpath so alias() conflicts with Gradle's unknown-classpath-version guard; pinning preserved via buildSrc implementation dep
- [Phase 04-event-bus-publishing]: Signing guarded by env-var null check so publishToMavenLocal succeeds locally without GPG; Pitfall 11 defended via --no-configuration-cache on publish commands (documented in convention plugin KDoc)
- [Phase 05-ci-baseline-skill-observability]: integrationTest zero-test guard uses addTestListener(TestListener) + AtomicInteger (val holding mutable state), not afterSuite — afterSuite is deprecated Gradle 9 / removed Gradle 10 / config-cache-incompatible (D-10)
- [Phase 05-ci-baseline-skill-observability]: integrationTest reuses existing src/test source set (no src/integrationTest move, D-09) and is decoupled from check/build so default ./gradlew build stays fast and Docker-free (D-11)
- [Phase 05-ci-baseline-skill-observability]: integration-test CI job on [arc-runner-unityinflow] needs:build with a docker info pre-flight emitting ::error title=Docker unavailable:: as a RUNNER CONFIG ERROR before tests run (D-14); zero-test assertion lives entirely in the Gradle guard, no CI-side XML parsing (D-15)
- [Phase 05-ci-baseline-skill-observability]: kore-storage integrationTest zero-test guard uses addTestListener(TestListener) + AtomicInteger + doLast throwing GradleException (NOT afterSuite — removed in Gradle 10 / config-cache-incompatible); task reuses src/test source set (D-09), decoupled from build/check (D-11)
- [Phase 05-ci-baseline-skill-observability]: integration-test CI job on self-hosted [arc-runner-unityinflow], needs:build, with a docker info pre-flight failing as a RUNNER CONFIG ERROR (D-14); zero-test assertion lives in the Gradle guard, no CI-side XML parsing (D-15)
- [Phase 05-ci-baseline-skill-observability]: SkillRegistry.activateFor() now returns List<ActivatedSkill> (breaking, no compat shim — D-01); ActivatedSkill(name, prompt) is a stdlib-only data class in kore-core port package, NOT @Serializable, never crosses the bus (D-02)
- [Phase 05-ci-baseline-skill-observability]: AgentLoop builds the kore.skill.activate span with literal key strings (kore.skill.names string-array / count / duration_ms) because kore-core cannot import KoreAttrs from kore-observability — KoreAttrs is the mirror source-of-truth, Plan 05-04 adds the constants; span name held as a private companion const SKILL_ACTIVATE_SPAN mirroring KoreSpans.SKILL_ACTIVATE
- [Phase 05-ci-baseline-skill-observability]: var-free span guard — val arrayOfNulls<List<ActivatedSkill>>(1) holder assigned via .also in try, read with .orEmpty() in finally so names/count/duration compute even when activateFor throws (span ALWAYS ends, D-04 / Pitfall 5); no var, no !!; nanoTime for duration
- [Phase 05-ci-baseline-skill-observability]: span-always / event-on-match asymmetry (D-04 vs D-07) — kore.skill.activate span emits unconditionally (incl. count=0), AgentEvent.SkillActivated emits only on >=1 match; prompts injected from ActivatedSkill.prompt, only skillNames (not prompts) reach the event payload (D-06)
- [Phase 05-ci-baseline-skill-observability]: observer when branches (kore-observability/kore-dashboard) intentionally NOT touched this plan — their else -> Unit absorbs SkillActivated without breaking compile (RESEARCH Pitfall 1); observer reactions + parenting test are Plan 05-03
- [Phase ?]: Plan 05-02: SkillRegistry.activateFor returns List<ActivatedSkill> (breaking, no shim); kore.skill.activate span always emitted incl count=0 and on-throw; SkillActivated event only on >=1 match (D-01/D-04/D-07)
- [Phase 05-ci-baseline-skill-observability]: Plan 05-04: KoreAttrs.SKILL_NAMES/SKILL_COUNT/SKILL_DURATION_MS added as the single source of truth for the kore.skill.* attribute keys (mirroring AgentLoop's inline literals); KoreTracer.withSpan gained an `is List<*>` branch that sets a native OTel string-array via AttributeKey.stringArrayKey + filterIsInstance<String>() — no comma-join, no UNCHECKED_CAST, no !! (D-03). OBSV-03 now complete on both emission and support sides.
- [Phase 05-ci-baseline-skill-observability]: Plan 05-04: KoreAttrs.SKILL_NAMES/SKILL_COUNT/SKILL_DURATION_MS added as the single source of truth for kore.skill.* keys; AgentLoop mirrors the literal strings inline (cannot import KoreAttrs) — a KoreTracerTest asserts constant==literal to prevent drift
- [Phase 05-ci-baseline-skill-observability]: Plan 05-04: KoreTracer.withSpan gains an is List<*> branch setting a native OTel string-array via AttributeKey.stringArrayKey + filterIsInstance<String>() (no UNCHECKED_CAST, no !!, no comma-join); is List<*> not is List<String> due to JVM type erasure
- [Phase 05-ci-baseline-skill-observability]: Plan 05-03: all three event-bus observers explicitly handle AgentEvent.SkillActivated before else -> Unit (RESEARCH Pitfall 1 — silent drop guarded): EventBusMetricsObserver moves kore.skills.activated counter once per skill name (tag agent_name+skill_name, D-24 low-cardinality) + records a per-run kore.skills.activate.duration DistributionSummary (Open-Q 2 — percentiles over a plain counter); EventBusSpanObserver + EventBusDashboardObserver are documented no-ops (D-08 — AgentLoop already emits the in-process span; a second would duplicate it)
- [Phase 05-ci-baseline-skill-observability]: Plan 05-03: OBSV-03 parenting proven through a REAL AgentLoop driven via ObservableAgentRunner with the SAME SDK tracer wired into both the runner's KoreTracer and the loop — skillSpan.parentSpanContext.spanId == agentRunSpan.spanId, same traceId; bare-loop would emit a ROOT skill span (Pitfall 2), so the test must go through the runner. Phase 5 now fully complete (CI-01/CI-02/OBSV-03/OBSV-04)
- [Phase ?]: Plan 05-03: explicit SkillActivated branch before else->Unit in all 3 observers (silent-drop guard); per-run DistributionSummary for skill duration; OBSV-03 parenting proven via real AgentLoop through ObservableAgentRunner into shared InMemorySpanExporter
- [Phase ?]: [Phase 06-real-budget-enforcement] Plan 06-01: kore-budget is a Spring-free library module (D-06); BudgetBreakerAdapter wraps budget-breaker TokenTracker in a ConcurrentHashMap<String,TokenTracker> keyed by agentId — checkBudget=!isAboveHardLimit (BUDG-06), per-agentId isolation for free (BUDG-07), defensive catch(BudgetException) so no library exception escapes the byte-identical port (D-00); getUsage read off the tracker
- [Phase ?]: [Phase 06-real-budget-enforcement] Plan 06-02: BudgetBreakerAutoConfiguration triple-gate (@ConditionalOnClass string form + @ConditionalOnProperty kore.budget.enabled=true + @ConditionalOnMissingBean(BudgetEnforcer::class)) makes BudgetBreakerAdapter the sole BudgetEnforcer bean only when kore-budget is present AND enabled=true; InMemoryBudgetEnforcer stays the zero-config default otherwise (BUDG-05). No bean-ordering needed — only one gate satisfiable per app. 4-scenario ApplicationContextRunner matrix (incl. FilteredClassLoader, T-06-04) proves it; enabled defaults false, defaultMaxTokens reused as single limit (D-01)

### Pending Todos

None yet.

### Blockers/Concerns

- ~~budget-breaker (Tool 05) not yet shipped~~ — RESOLVED: `io.github.unityinflow:budget-breaker:0.0.1` published to Maven Central (verified 2026-06-12). Phase 6 builds the real adapter. Note: `budget-breaker-spring-boot-starter` still unpublished — kore-budget auto-config gates on core library class presence only.
- Docker availability on arc-runner-unityinflow not pre-verified — the Phase 5 `integration-test` CI job now includes a `docker info` pre-flight (commit 24e17a2) that fails loudly as a config error before tests run. First PR CI run is the manual verification that Docker is actually installed on the runner.
- `BudgetBreakerAdapter` lifecycle bridging is MEDIUM confidence (event-subscription model for `withBudget` scopes) — fallback to `TokenTracker` low-level API documented in research SUMMARY.md.

## Deferred Items

Items acknowledged and deferred at milestone close on 2026-04-26.

**All verification gaps closed 2026-04-26 post-close** via `/gsd-verify-work 01` and `/gsd-verify-work 02`:

| Category | Item | Status |
|----------|------|--------|
| debug | knowledge-base | benign — resolved-knowledge index file, not an open session |
| ~~verification_gap~~ | ~~Phase 01: 01-VERIFICATION.md~~ | ✅ resolved 2026-04-26 — UAT 3/3 pass, status flipped to verified |
| ~~verification_gap~~ | ~~Phase 02: 02-VERIFICATION.md~~ | ✅ resolved 2026-04-26 — UAT 2/2 pass, status flipped to verified |

~~**Outstanding follow-up:** kore-storage integrationTest task~~ — now in scope as Phase 5 (CI-01, CI-02).

## Session Continuity

Last session: 2026-06-21T16:10:01.342Z
Stopped at: Phase 7 context gathered
Resume file: .planning/phases/07-hierarchical-agents/07-CONTEXT.md
