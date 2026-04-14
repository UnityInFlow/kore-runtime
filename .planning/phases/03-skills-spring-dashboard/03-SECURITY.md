---
phase: 03
slug: skills-spring-dashboard
status: verified
threats_total: 14
threats_closed: 14
threats_open: 0
asvs_level: 1
created: 2026-04-13
---

# Phase 03 — Security: skills-spring-dashboard

> Per-phase security contract. Threat register is built from the `<threat_model>` blocks
> in the five Phase 03 plans (`03-01` .. `03-05`). Every threat has been verified against
> the implementation at HEAD and carries a file:line citation.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Filesystem → SkillLoader | User-configurable directory (`kore.skills.directory`, default `./kore-skills`) loaded via Jackson YAMLMapper | YAML documents, skill prompt strings |
| Classpath → SkillLoader | `META-INF/kore/skills/*.yaml` resources enumerated via `ClassLoader.getResources` | YAML documents shipped inside JARs |
| Skill prompt → LLM system message | Activated skill prompts prepended to agent history as `Role.System` | Free-form prompt strings |
| `application.yml` → `KoreProperties` | Host-app configuration flows into kore beans via Spring Boot 4 `@ConfigurationProperties` constructor binding | API keys, R2DBC URL, dashboard port/path |
| HTTP client → Spring Actuator `/actuator/kore` | Host app's Spring context exposes kore's aggregate health endpoint | Status, agent count, enforcer class name, version |
| HTTP client → Ktor dashboard port (default `8090`) | Separate embedded Ktor CIO server bound by `DashboardServer` | HTML fragments, active agents, recent runs, cost summary |
| EventBus → `EventBusDashboardObserver` | In-process event stream captured into a bounded `ConcurrentHashMap` | `AgentStarted`/`AgentCompleted`/`LLMCallCompleted` |
| `AuditLog` → dashboard HTML fragments | Database records surfaced in HTMX fragment responses | `AgentRunRecord`, `AgentCostRecord` (no task content) |
| PostgreSQL R2DBC → `PostgresAuditLogAdapter` | Exposed-R2DBC DSL queries against Flyway-managed schema | Run history, LLM calls |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-03-01 | Tampering | `SkillLoader` — filesystem + JAR YAML parsing | mitigate | Jackson `YAMLMapper` with only `kotlinModule()` — **no polymorphic type resolution**, no `enableDefaultTyping`. All parse/IO failures caught (`JsonProcessingException` + `IOException`) and skipped per-file. `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt:26-29` (mapper config), `:117-125` (JAR catch), `:140-154` (filesystem catch) | closed |
| T-03-02 | Denial of Service | `PatternMatcher` — regex activation patterns | mitigate | Patterns pre-compiled once at construction; `PatternSyntaxException` caught and offending pattern skipped; matches use `containsMatchIn` (no full backtracking match). `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/internal/PatternMatcher.kt:21-34` | closed |
| T-03-03 | Information Disclosure | `AuditLog.queryRecentRuns` — task content in results | accept | `AgentRunRecord` data class intentionally contains only `agentName`, `resultType`, token counts, `durationMs`, `completedAt` — no `task.input` field. `PostgresAuditLogAdapter.queryRecentRuns` explicitly does not project `AgentRunsTable.task`. `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:124-159` | closed |
| T-03-04 | Spoofing | `SkillLoader` — classpath resource enumeration | accept | Classpath resources come from the host app's own JAR/classpath. An attacker with classpath write access is outside kore's trust boundary. Documented as deployment-time responsibility. `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt:44-71` | closed |
| T-03-05 | Information Disclosure | `KoreActuatorEndpoint` — `/actuator/kore` payload | mitigate | `@ReadOperation health()` returns only `status`, `agentsActive` (int), `budgetEnforcer` (class simple name), `version`. No API keys, task content, LLM responses, or URLs. Endpoint authorization delegated to host app's Spring Security config. `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt:64-71` | closed |
| T-03-06 | Information Disclosure | `KoreProperties` — API keys in `application.yml` | accept | `ClaudeProperties.apiKey`, `OpenAiProperties.apiKey`, `GeminiProperties.apiKey` default to empty strings. README hero demo binds them from environment variables (`${KORE_CLAUDE_API_KEY}`, `System.getenv("ANTHROPIC_API_KEY")`, `System.getenv("OPENAI_API_KEY")`). `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt:37-65`, `README.md:47,81,137-138` | closed |
| T-03-07 | Elevation of Privilege | `KoreAutoConfiguration` conditional wiring | mitigate | Plan 03-04 removed all `Class.forName` / `Constructor.newInstance` reflection from `KoreAutoConfiguration`. Dashboard, storage, skills, and LLM backends are now `compileOnly` dependencies with direct constructor calls guarded by `@ConditionalOnClass(name = [...])` string form. Verified: `grep Class.forName kore-spring/src/main/kotlin/` returns zero matches. `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` | closed |
| T-03-08 | Denial of Service | `KoreAutoConfiguration` eager class loading | mitigate | All optional-module configurations nested as `@Configuration(proxyBeanMethods = false)` inner classes with `@ConditionalOnClass(name = [...])` string form. Spring never touches the class objects until the condition is evaluated — no `ClassNotFoundException` on partial classpath. RESEARCH.md Pitfall 12 applied. `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` | closed |
| T-03-09 | Information Disclosure | `DashboardServer` — no authentication on Ktor port | accept | Dashboard is operator/developer tooling on a separate port (default `8090`) from the main Spring app (`8080`). Disabling is a single property (`kore.dashboard.enabled=false`). README Section "Security & deployment" (see Out-of-Scope below) documents the production deployment contract. Accepted for v0.0.1. `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt:97-116`, `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt:87-91` | closed |
| T-03-10 | Injection | `Fragments.kt` / `Components.kt` — HTML rendering | mitigate | `kotlinx.html` DSL auto-escapes all string content inserted via `+text`. Every user-derived field (`state.name`, `state.currentTask`, `run.agentName`, `cost.agentName`, token counts) uses `+` text concatenation — never `unsafe {}`. The only `unsafe { +koreEmbeddedCss }` call in `PageShell.kt:162` embeds a static, compile-time CSS constant, not user data. `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt:37-170`, `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt:162` | closed |
| T-03-11 | Denial of Service | `EventBusDashboardObserver` — unbounded `ConcurrentHashMap` | mitigate | Observer enforces `maxTrackedAgents = 10_000` cap on every `AgentStarted`. Oldest entries evicted deterministically by `AgentState.startedAt` (ME-02 fix) before the new insert. `AgentCompleted` removes entries normally. `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt:30-74` | closed |
| T-03-12 | Information Disclosure | `queryRecentRuns` / `queryCostSummary` — task input content | accept | Neither `AgentRunRecord` nor `AgentCostRecord` contains a task-input field. `PostgresAuditLogAdapter.queryRecentRuns` selects only `agentName, resultType, finishedAt, tokensIn, tokensOut, durationMs`. `queryCostSummary` selects only `id, agentName, tokensIn, tokensOut`. Task content never crosses the read boundary. `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:128-134,186-190` | closed |
| T-03-13 | Information Disclosure | README hero demo — API key example | mitigate | Every hero-demo example binds API keys from environment variables: `model = claude(apiKey = System.getenv("ANTHROPIC_API_KEY"))` and `api-key: ${KORE_CLAUDE_API_KEY}`. No literal key values anywhere in README. `README.md:47,81,137-138` | closed |
| T-03-14 | Denial of Service | Integration test — live Ktor server binding port 8090 | mitigate | `KoreIntegrationTest` uses `@TestPropertySource(properties = ["kore.dashboard.enabled=false"])` to prevent Ktor from binding a real port during Spring-context tests. Bean creation is verified without opening a socket. `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt`, `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/DashboardDegradedModeSpringTest.kt` | closed |
| T-03-05-01 | Information Disclosure | `AuditLog.isPersistent` discriminator on port interface | accept | Non-sensitive boolean discriminator. Default `false` (InMemoryAuditLog inherits); `PostgresAuditLogAdapter.isPersistent = true`. No PII or credentials exposed. `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt:33`, `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:51` | closed |
| T-03-05-02 | Denial of Service | `DashboardServer` start→stop→start lifecycle | mitigate | `AtomicReference`-backed `scopeRef` + `observerRef` swap with `getAndSet(null)` semantics. Each `start()` constructs a fresh `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and observer — restart cannot launch on a cancelled scope (HI-01 fix). `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt:88-125`, pinned by `DashboardServerRestartTest` | closed |
| T-03-05-03 | Tampering | `dataServiceForTest` / `scopeForTest` / `observerForTest` accessors | accept | `scopeForTest`/`observerForTest` are `internal` (visible only within kore-dashboard's own test source set). `dataServiceForTest` is `public` (required because Kotlin `internal` is not cross-module visible to kore-spring tests) but returns a reference to an existing immutable-by-API `DashboardDataService` — no state mutation. `-Test` suffix is the reviewer guard. Usages confirmed only in test source sets. `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt:134-174` | closed |

*Total: 17 threats — 14 STRIDE entries from plans 03-01..03-04 plus 3 gap-closure entries (T-03-05-0N) from plan 03-05.*

*Note: T-03-05 appears twice in the source plans — once in plan 03-02 as the STRIDE actuator entry and again in plan 03-05 using a disambiguated `T-03-05-0N` numbering for the gap closure. Both are preserved above.*

*Status: `closed` · `open`*
*Disposition: `mitigate` (implementation required) · `accept` (documented risk) · `transfer` (third-party)*

---

## Mitigation Evidence Summary

| # | Verification | File:Line |
|---|---|---|
| YAML parsing is not polymorphic | `grep enableDefaultTyping kore-skills/` → **0 matches** | — |
| YAML parse failures skipped | `parseSkillFileSafely` catches `JsonProcessingException` + `IOException` | `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt:140-154` |
| JAR URL safe loading | `JarURLConnection.jarFile` instead of path slicing (ME-07) | `SkillLoader.kt:82-128` |
| Regex RE-DoS guard | `PatternSyntaxException` catch + `containsMatchIn` | `PatternMatcher.kt:21-34` |
| No reflection in autoconfig | `grep Class.forName kore-spring/src/main/kotlin/` → **0 matches** | — |
| `@ConditionalOnClass(name=[...])` string form | — | `KoreAutoConfiguration.kt` |
| Actuator surface minimal | Keys: status, agentsActive, budgetEnforcer, version | `KoreActuatorEndpoint.kt:64-71` |
| kotlinx.html escapes user data | All user fields rendered via `+text`; no `unsafe {}` except static CSS constant | `Fragments.kt:50-56`, `PageShell.kt:162` |
| Observer memory cap | `maxTrackedAgents = 10_000` with startedAt-ordered eviction | `EventBusDashboardObserver.kt:30-74` |
| No raw SQL in audit adapter | Pure Exposed DSL (`select`, `leftJoin`, `where`, `orderBy`, `limit`) | `PostgresAuditLogAdapter.kt:124-212` |
| Query projections exclude PII | `select(agentName, resultType, finishedAt, tokensIn, tokensOut, durationMs)` — never `task` | `PostgresAuditLogAdapter.kt:128-134` |
| README env-var binding | No literal keys; `System.getenv` / `${KORE_CLAUDE_API_KEY}` | `README.md:47,81,137-138` |
| Integration test disables port bind | `@TestPropertySource(properties = ["kore.dashboard.enabled=false"])` | `KoreIntegrationTest.kt` |
| Dashboard restart safety | AtomicReference scope swap + fresh SupervisorJob per start() | `DashboardServer.kt:88-125` |
| isPersistent discriminator | Default `false`; Postgres override `true` | `AuditLog.kt:33`, `PostgresAuditLogAdapter.kt:51` |

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-03-01 | T-03-03 | AuditLog read methods do not project task content. PII boundary is the write path (T-02-05), already flagged in Phase 02. | phase-03 plan | 2026-04-13 |
| AR-03-02 | T-03-04 | Classpath skill enumeration trusts the host application's own JAR layout. Attackers with classpath-write capability are outside kore's trust boundary. | phase-03 plan | 2026-04-13 |
| AR-03-03 | T-03-06 | API key storage delegated to host application's environment / secret store. KoreProperties fields default to blank; auto-config beans are gated by `@ConditionalOnProperty`. | phase-03 plan | 2026-04-13 |
| AR-03-04 | T-03-09 | Dashboard has no built-in authentication in v0.0.1. Dashboard is operator tooling on a separate port (default 8090); production deployments must place it behind a reverse proxy or disable it via `kore.dashboard.enabled=false`. README and `DashboardServer` KDoc document the deployment contract. Explicit dashboard auth is deferred to a future ops/hardening phase. | phase-03 plan | 2026-04-13 |
| AR-03-05 | T-03-12 | Parallel accept to AR-03-01 for the cost-summary read path. No task content or PII projected. | phase-03 plan | 2026-04-13 |
| AR-03-06 | T-03-05-01 | `AuditLog.isPersistent` is a boolean discriminator; no sensitive data. Required to make the dashboard's degraded-mode notice render correctly when the default InMemoryAuditLog is wired. | phase-03 plan (gap closure) | 2026-04-13 |
| AR-03-07 | T-03-05-03 | Test-only `*ForTest()` accessors are named with `-Test` suffix as a reviewer guard. `scopeForTest`/`observerForTest` are Kotlin `internal` (same-module only). `dataServiceForTest` is `public` by necessity (Kotlin `internal` is not cross-module visible to kore-spring tests) but returns an immutable-API reference and has no non-test callers anywhere in the codebase. A future ktlint custom rule can enforce the suffix mechanically. | phase-03 plan (gap closure) | 2026-04-13 |

---

## Out-of-Scope / Deferred Items

The following controls are **explicitly not in Phase 03 scope** and are tracked for future phases:

1. **Dashboard authentication (T-03-09, AR-03-04).**
   v0.0.1 ships the dashboard with no built-in auth layer. Operators who expose port 8090 publicly are responsible for fronting it with a reverse proxy (nginx, Caddy, Traefik, Spring Gateway) or disabling it. README documents `kore.dashboard.enabled=false` as the kill switch. A Phase 4+ hardening pass may add optional Spring Security or Ktor `Authentication` plugin integration.

2. **TLS / HTTPS termination.**
   `kore-spring` is a library, not an application. TLS configuration is the responsibility of the hosting Spring Boot application and/or the deployment's ingress layer. The Ktor dashboard server likewise binds plain HTTP on its configured port — TLS termination belongs upstream.

3. **Dashboard bind-host restriction.**
   Ktor CIO `embeddedServer(CIO, port = properties.port)` binds `0.0.0.0` by default. Restricting the bind host to `127.0.0.1` via a property (`kore.dashboard.host`) is a reasonable hardening step and is recommended for a follow-up issue but is not a Phase 03 blocker.

4. **SQL GROUP BY for `queryCostSummary`.**
   Current implementation aggregates in Kotlin with a `costSummaryMaxRows = 100_000` safety ceiling (ME-03). A proper time-window predicate or SQL GROUP BY is a correctness enhancement, not a security mitigation, and is deferred.

5. **Budget enforcement at the dashboard layer.**
   `budget-breaker` integration lands in a future phase; Phase 03 uses `InMemoryBudgetEnforcer` only.

6. **Skill content signing / provenance.**
   Loaded YAML skills run as trusted prompts. Future work could add signature verification on filesystem skills; not an ASVS-L1 requirement.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-13 | 17 | 17 | 0 | gsd-secure-phase / Claude |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter
- [x] Implementation files not modified by this audit (read-only verification)

**Approval:** verified 2026-04-13
