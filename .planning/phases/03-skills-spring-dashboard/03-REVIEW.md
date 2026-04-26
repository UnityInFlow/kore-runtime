---
phase: 03-skills-spring-dashboard
reviewed: 2026-04-14T00:00:00Z
depth: standard
files_reviewed: 34
files_reviewed_list:
  - kore-core/build.gradle.kts
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/AgentBuilder.kt
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/SkillRegistry.kt
  - kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopSkillTest.kt
  - kore-dashboard/build.gradle.kts
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/AgentState.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardRouting.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt
  - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt
  - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt
  - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserverTest.kt
  - kore-skills/build.gradle.kts
  - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt
  - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapter.kt
  - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillYamlDef.kt
  - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/internal/PatternMatcher.kt
  - kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillLoaderTest.kt
  - kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapterTest.kt
  - kore-spring/build.gradle.kts
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt
  - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt
  - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt
  - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt
  - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt
  - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterQueryTest.kt
  - settings.gradle.kts
findings:
  blocker: 0
  high: 2
  medium: 8
  low: 6
  nit: 3
  total: 19
status: issues_found
---

# Phase 3: Code Review Report

**Reviewed:** 2026-04-14
**Depth:** standard
**Files Reviewed:** 34
**Status:** issues_found

## Summary

Phase 3 lands a large, well-structured vertical slice across five modules (kore-core skill port, kore-skills adapter, kore-storage read queries, kore-spring auto-config, kore-dashboard Ktor side-car) plus an integration test that boots the whole Spring context end-to-end. The hexagonal boundaries hold: kore-core stays zero-runtime-dep (OpenTelemetry `compileOnly`, SkillRegistry defaults to `NoOpSkillRegistry`), kore-dashboard does not depend on kore-spring (property-bag adapter pattern), and conditional bean wiring uses the string-form `@ConditionalOnClass` required by Spring Boot 4 (Pitfall 12 honoured).

The bulk of the phase-3 pitfall checklist is respected: `wait=false` on Ktor (Pitfall 3), observer started before the engine (Pitfall 6), `InMemoryAuditLog` implements the new read methods (Pitfall 7), `ConversationMessage.Role.System` exists and is used (Pitfall 8), no `kaml` (Pitfall 4), and the actuator endpoint exposes only aggregate counts (T-03-05).

**However**, there are real correctness and UX problems worth fixing before v0.0.1:

1. **`DashboardServer` cannot restart** after Spring SmartLifecycle `stop()` because the internal `CoroutineScope` is cancelled but never recreated on the next `start()`. Any code path that stops and restarts the dashboard (e.g. Spring context refresh) will silently produce an empty active-agents view. (High)
2. **D-27 degraded mode never renders in the default "no kore-storage" path** because `KoreAutoConfiguration` wires `InMemoryAuditLog` as the `AuditLog` bean, and `DashboardServer` only routes through the `null`-auditLog branch via an identity check on a private sentinel. Users without kore-storage see a "No runs recorded" empty state instead of the "History unavailable — kore-storage not configured" notice specified by UI-SPEC. (High)
3. **`SkillLoader.parseSkillFileSafely` catches only `MismatchedInputException` / `JsonMappingException`**, not `JsonParseException` (malformed YAML syntax) or `IOException` — contradicts the docstring claim that "malformed YAML files are logged to stderr and skipped" and will crash `loadAll()` on the first syntactically broken YAML. (Medium)
4. **`EventBusDashboardObserver` capacity eviction is non-deterministic** because `ConcurrentHashMap.keys` has no insertion order, so the "oldest entries" comment is misleading and the T-03-11 DoS bound evicts arbitrary agents. (Medium)
5. **`queryCostSummary` pulls the entire `agent_runs × llm_calls` join into memory** with no `WHERE` clause or `LIMIT`, so dashboards running against a long-lived database OOM on the 10-second poll interval. (Medium)

Most phase-3 tests genuinely exercise their contracts (AgentLoopSkillTest records the first-call history, DashboardRoutingTest uses `ktor-server-test-host` to hit real routes, PostgresAuditLogAdapterQueryTest seeds Testcontainers-backed data). One integration test (`KoreIntegrationTest`) only verifies bean wiring; it does not exercise the agent end-to-end — acceptable for the scope but noted below.

## High

### HI-01: DashboardServer restart leaks a dead CoroutineScope and silently breaks the observer

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt:82-107`

**Issue:** The internal supervisor scope is a `private val` initialised once at construction:

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

`stop()` calls `scope.cancel()`, which transitions the scope to a terminal state. A subsequent `start()` will:
1. Re-check `engine.get() != null` (now `null`, so proceed),
2. Call `observer.startCollecting()` → `scope.launch { ... }` on a **cancelled** scope — the launch returns a `CompletedJob` and the collector never runs,
3. Start a new Ktor engine successfully.

Result: the dashboard serves `/kore/fragments/active-agents` but the map is permanently empty. No exception is thrown. Spring will exercise this code path during any context refresh (`ApplicationContext.refresh()` called twice, dev-tools reload, actuator `/restart`).

**Fix:** Hold the scope in an `AtomicReference` (matching the engine pattern already used in this file) and recreate it on each `start()`:

```kotlin
private val scopeRef = AtomicReference(CoroutineScope(SupervisorJob() + Dispatchers.Default))
private val observerRef = AtomicReference(EventBusDashboardObserver(eventBus, scopeRef.get()))

override fun start() {
    if (engine.get() != null) return
    // Rebuild scope + observer on every start so stop() -> start() works.
    val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scopeRef.set(freshScope)
    val freshObserver = EventBusDashboardObserver(eventBus, freshScope)
    observerRef.set(freshObserver)
    freshObserver.startCollecting()
    // ... engine start as before, route handler closures must use observerRef.get() ...
}

override fun stop() {
    engine.getAndSet(null)?.stop(1_000, 5_000)
    scopeRef.getAndSet(CoroutineScope(SupervisorJob() + Dispatchers.Default)).cancel()
}
```

Alternatively, document explicitly that `DashboardServer` is single-use and assert in `start()` with a clear error if the scope is already cancelled.

Also consider: `start()` currently uses `if (engine.get() != null) return` which is not atomic under concurrent calls. Low-probability in practice because `SmartLifecycle.start()` is called by the Spring lifecycle processor on a single thread, but worth using `compareAndSet(null, newEngine)` or a `synchronized(this)` block for belt-and-braces.

---

### HI-02: Degraded-mode dashboard notice never renders with the default InMemoryAuditLog wiring

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt:84`
**File:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt:62-63, 222-233`

**Issue:** D-27 and UI-SPEC require the recent-runs and cost-summary fragments to render the "History unavailable — kore-storage not configured" notice when the host application has no real storage adapter. The implementation plumbs this through a `null` check:

```kotlin
// DashboardServer.kt:84
private val dataService = DashboardDataService(auditLog.takeUnless { it === InertAuditLog })
```

But `InertAuditLog` is a **private** sentinel used only by the 3-arg `(EventBus, AuditLog?, DashboardConfig)` convenience constructor — it never appears in the Spring-wired path. When `KoreAutoConfiguration` runs in a host without kore-storage on the classpath:

1. `StorageAutoConfiguration` is skipped,
2. `@ConditionalOnMissingBean(AuditLog::class)` fires on `inMemoryAuditLog()` → the bean is an `InMemoryAuditLog`,
3. `dashboardServer(...)` injects that `InMemoryAuditLog` as `auditLog`,
4. `DashboardServer` calls `auditLog.takeUnless { it === InertAuditLog }` → `InMemoryAuditLog !== InertAuditLog` → `dataService.hasStorage()` returns `true`,
5. `DashboardDataService.getRecentRuns()` delegates to `InMemoryAuditLog.queryRecentRuns(20)` which returns `emptyList()`,
6. `recentRunsFragment(emptyList(), degraded = false)` renders the **"No runs recorded"** empty-state instead of the degraded notice.

The user is left thinking "no runs have happened yet" when the real reason is "you never configured a database". This silently contradicts UI-SPEC.md (verified in `DashboardRoutingTest.GET recent-runs fragment with null AuditLog renders the degraded notice`, which tests the convenience constructor only).

**Fix (preferred):** Add a marker interface or an explicit method on `AuditLog` that signals "backed by persistent storage":

```kotlin
// kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt
interface AuditLog {
    // ... existing methods ...
    /** True when this adapter is backed by durable storage; false for in-memory stubs. */
    val isPersistent: Boolean get() = false
}

// InMemoryAuditLog keeps the default `false`.
// PostgresAuditLogAdapter overrides `val isPersistent = true`.
```

Then in `DashboardServer`:

```kotlin
private val dataService = DashboardDataService(
    auditLog.takeIf { it.isPersistent && it !== InertAuditLog }
)
```

**Fix (alternative):** Do an `is InMemoryAuditLog` type check in `DashboardServer` — quicker but leaks the concrete type of a core-internal class across module boundaries. Not recommended.

**Fix (alternative):** Do the "is persistent?" check in `KoreAutoConfiguration.dashboardServer(...)` and pass `null` via the 4-arg convenience constructor when the auto-wired AuditLog is the default InMemoryAuditLog. Requires exposing the nullable-auditlog convenience constructor to kore-spring, which currently only knows the primary 3-arg signature.

Either way, the current code ships a broken UX for the zero-config path and should block v0.0.1.

## Medium

### ME-01: SkillLoader does not catch YAML parse errors — one broken file crashes loadAll()

**File:** `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt:133-142`

**Issue:** The docstring on the class says "Malformed YAML files are logged to stderr and skipped — a single broken skill never fails the whole loader (T-03-01 / Pitfall 5)." `parseSkillFileSafely` catches only `MismatchedInputException` and `JsonMappingException`:

```kotlin
try { mapper.readValue<SkillYamlDef>(file) }
catch (ex: MismatchedInputException) { ... }
catch (ex: JsonMappingException) { ... }
```

Jackson's class hierarchy (2.x and 3.x):
- `JsonMappingException extends JsonProcessingException` — caught. ✓
- `JsonParseException extends JsonProcessingException` — **not caught**. ✗
- Low-level I/O problems throw `IOException` (superclass of `JsonProcessingException`) — **not caught**. ✗

A YAML file with invalid syntax (e.g. unbalanced quotes, tab indentation where spaces are required) throws `JsonParseException`, which propagates up through `loadYamlsFromFileUrl` → `loadFromClasspath` → `loadAll` and **aborts skill loading entirely**. This also crashes the Spring `SkillsAutoConfiguration.skillRegistry(...)` bean factory method, which surfaces as a `BeanCreationException` during context startup. One bad user skill file therefore takes down the entire application.

**Fix:** Catch the common supertype:

```kotlin
private fun parseSkillFileSafely(file: File): SkillYamlDef? =
    try {
        mapper.readValue<SkillYamlDef>(file)
    } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
        System.err.println("kore-skills: malformed YAML at ${file.path}: ${ex.message}")
        null
    } catch (ex: java.io.IOException) {
        System.err.println("kore-skills: cannot read skill file ${file.path}: ${ex.message}")
        null
    }
```

Apply the same change to the `JarFile` entry parsing branch in `loadYamlsFromJarUrl` (lines 112-118).

Add a test: load a YAML fixture containing invalid syntax (`name: "unterminated` with no closing quote) and assert `loadAll()` returns the other valid skills without throwing.

---

### ME-02: EventBusDashboardObserver capacity eviction has no defined ordering

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt:46-51`

**Issue:** The T-03-11 DoS bound claims to "evict oldest entries":

```kotlin
if (activeAgents.size >= maxTrackedAgents) {
    activeAgents.keys
        .take(activeAgents.size - maxTrackedAgents + 1)
        .forEach { activeAgents.remove(it) }
}
```

`ConcurrentHashMap.keys` has **no defined iteration order** — it is not a `LinkedHashMap`. `.take(N)` picks whichever keys the segment walk happens to visit first, which in a rehashed map can be any of them. The code evicts *arbitrary* agents, not *oldest* agents. An adversary can still pin the map at capacity by continuously emitting `AgentStarted` faster than `AgentCompleted`, but the claim in the comment and the class-level docstring is wrong, and real production agents may be evicted while long-running zombies stick around forever.

**Fix:** Track insertion order explicitly. `Collections.synchronizedMap(LinkedHashMap<>())` (giving up ConcurrentHashMap's lock-striping) or a `ConcurrentSkipListMap<Long, String>` keyed by `Instant.now().toEpochMilli()` and reverse-indexed by agentId. Simplest:

```kotlin
// AgentState already has startedAt — sort on that.
if (activeAgents.size >= maxTrackedAgents) {
    val victims = activeAgents.entries
        .sortedBy { it.value.startedAt }
        .take(activeAgents.size - maxTrackedAgents + 1)
        .map { it.key }
    victims.forEach { activeAgents.remove(it) }
}
```

This evicts by `AgentState.startedAt`, matching the "oldest" semantics actually documented. Note the O(N log N) sort fires only when the map hits 10k entries, which is acceptable for a DoS mitigation path.

---

### ME-03: queryCostSummary loads the entire join into memory

**File:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:144-169`

**Issue:** `queryCostSummary` was deliberately moved from SQL `GROUP BY` to Kotlin-side aggregation to sidestep Exposed R2DBC dialect quirks. The implementation has **no `WHERE` clause and no `LIMIT`**:

```kotlin
AgentRunsTable
    .innerJoin(LlmCallsTable)
    .select(AgentRunsTable.id, AgentRunsTable.agentName,
            LlmCallsTable.tokensIn, LlmCallsTable.tokensOut)
    .toList()   // <-- pulls every (run × call) row into the JVM
    .groupBy { it[AgentRunsTable.agentName] }
    .map { ... }
```

The class-level comment says "dashboard scale (<10k rows) is well within safe in-memory bounds", but the dashboard polls this endpoint every **10 seconds** (D-28) and makes no effort to limit the query by time window. A long-lived kore deployment will accumulate millions of `llm_calls` rows; at that point every 10s poll drags megabytes of data over R2DBC and allocates on the event-loop thread. The OOM will surface as slow GC, cascading 503s, or JVM shutdown depending on heap size.

**Fix options:**

1. **Add a retention window** (preferred for v0.0.1):
   ```kotlin
   AgentRunsTable.innerJoin(LlmCallsTable)
       .select(AgentRunsTable.id, AgentRunsTable.agentName,
               LlmCallsTable.tokensIn, LlmCallsTable.tokensOut)
       .where { AgentRunsTable.finishedAt greater OffsetDateTime.now().minusDays(7) }
       // ...
   ```
   Make the window configurable via `kore.dashboard.cost-summary-window-days` in KoreProperties.

2. **Push the aggregation into SQL** (best for v0.1.0) — use Exposed R2DBC aggregation functions once the dialect quirks are resolved, or drop to raw SQL via `exec` for this query only.

3. **Cache the result in memory** with a short TTL (30s) and have the HTMX 10s poll hit the cache. Reduces read amplification by ~3x. Complements (1) and (2).

Add an issue comment referencing this finding if it is deferred to v0.1.0.

---

### ME-04: queryRecentRuns inner join hides runs with zero LLM calls

**File:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:98-133`

**Issue:** The query uses `innerJoin(LlmCallsTable)`. Any agent run that terminates *before* its first LLM call — `AgentResult.BudgetExceeded` on the pre-call budget check, `AgentResult.LLMError` from an auth failure on the very first request, `Cancelled` before the first call completes — inserts a row into `agent_runs` but never inserts into `llm_calls`. The inner join drops those rows, so the dashboard's "Recent Runs" view silently omits exactly the failures the user most needs to diagnose.

**Fix:** Use a left join and handle nullable LLM-call columns:

```kotlin
AgentRunsTable
    .leftJoin(LlmCallsTable)   // was: innerJoin
    // ... select same columns ...
    .map { row ->
        AgentRunRecord(
            agentName = row[AgentRunsTable.agentName],
            resultType = row[AgentRunsTable.resultType],
            inputTokens = row.getOrNull(LlmCallsTable.tokensIn) ?: 0,
            outputTokens = row.getOrNull(LlmCallsTable.tokensOut) ?: 0,
            durationMs = row.getOrNull(LlmCallsTable.durationMs)?.toLong() ?: 0L,
            completedAt = row[AgentRunsTable.finishedAt]?.toInstant() ?: Instant.EPOCH,
        )
    }
```

Add a test seeding an `agent_run` with no `llm_call` and asserting it appears in `queryRecentRuns(20)`.

Note: this also interacts with ME-03 — fixing ME-04 to `leftJoin` increases the cardinality and makes the cost-summary unbounded-query problem worse, reinforcing the need for a time window.

---

### ME-05: completedAt falls back to Instant.EPOCH for in-flight runs

**File:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:128-131`

**Issue:** `AgentRunsTable.finishedAt` is nullable (runs insert `finishedAt = OffsetDateTime.now()` on the create path, so all currently-completed rows are non-null — but future work that inserts an in-progress row will expose the null). The read path falls back to `Instant.EPOCH`:

```kotlin
completedAt = row[AgentRunsTable.finishedAt]?.toInstant() ?: Instant.EPOCH,
```

The dashboard fragment renders this as `1970-01-01 00:00:00` — a misleading timestamp. If the intent of the recent-runs fragment is "completed runs", the query should `WHERE finishedAt IS NOT NULL` and drop in-progress rows. If the intent is "all runs including in-progress", the record should expose `completedAt: Instant?` and the fragment should render "in progress" for null values.

**Fix:** Add `where { AgentRunsTable.finishedAt.isNotNull() }` to the query (pairs naturally with a window predicate per ME-03). Or, make `AgentRunRecord.completedAt` nullable and update the fragment. The first option is simpler.

---

### ME-06: EventBusDashboardObserver.startCollecting has no idempotency guard

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt:41-76`

**Issue:** `startCollecting()` calls `scope.launch { ... }` unconditionally. A double-call (e.g. by a misconfigured `DashboardServer.start()` being invoked twice before `stop()`) launches a second collector on the same scope, and both collectors independently mutate `activeAgents`. Worst-case: tokens are double-counted by the `LLMCallCompleted` branch because both collectors invoke `compute { state?.copy(tokensUsed = state.tokensUsed + delta) }` on the same event.

`DashboardServer.start()` does have a guard (`if (engine.get() != null) return`) which catches the common case. But the guard uses `engine`, not a start-collecting flag, so a sequence like:
1. `start()` — launches observer, fails to start Ktor (port conflict)
2. caller catches the exception and calls `start()` again
would re-enter `observer.startCollecting()` with the original collector still live.

**Fix:** Give the observer its own atomic start flag:

```kotlin
private val started = AtomicBoolean(false)

fun startCollecting() {
    if (!started.compareAndSet(false, true)) return
    scope.launch { ... }
}
```

Combined with the HI-01 fix (fresh scope on each `DashboardServer.start()`), also fresh-construct the observer.

---

### ME-07: SkillLoader jar URL parsing does not URL-decode paths with spaces

**File:** `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt:83-121`

**Issue:** The JAR URL decoder:

```kotlin
val path = url.path   // e.g. "file:/Users/foo/My%20Project/kore.jar!/META-INF/..."
val bangIdx = path.indexOf("!/")
val jarPath = path.substring("file:".length, bangIdx)
```

`URL.getPath()` returns the raw path — `%20` is not decoded. A user running kore from a path containing spaces or other special characters (very common on macOS: `~/Documents/My Project/`) will get `JarFile(File("/Users/foo/My%20Project/kore.jar"))` which fails with `FileNotFoundException`. The loader catches the exception and logs to stderr but silently drops every classpath skill including the bundled `code-review.yaml`.

**Fix:** Use `URI.create(url.toString()).toURL()` → `File(url.toURI())` round-trip, or more directly `java.net.URLDecoder.decode(path, StandardCharsets.UTF_8)` before the substring:

```kotlin
val decoded = java.net.URLDecoder.decode(url.path, java.nio.charset.StandardCharsets.UTF_8)
val bangIdx = decoded.indexOf("!/")
val jarPath = decoded.substring("file:".length, bangIdx)
```

Note also that `"file:".length` hardcodes the protocol — `jar:file:/...` and `jar:nested:/...` exist. A cleaner idiom is:

```kotlin
val jarUrlConnection = url.openConnection() as java.net.JarURLConnection
val jar = jarUrlConnection.jarFile
val entryPrefix = jarUrlConnection.entryName
```

`JarURLConnection` handles URL decoding and nested JAR protocols correctly.

---

### ME-08: KoreIntegrationTest never runs the agent end-to-end

**File:** `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt:73-128`

**Issue:** All five integration-test cases assert bean presence only — `testAgent.shouldNotBe(null)`, `dashboardServer.isRunning() shouldBe false`, etc. No test actually calls `testAgent.run(AgentTask(...))` to exercise the wired graph: the EventBus → observer subscription, the SkillRegistryAdapter activation, the budget enforcer, the actuator counter ticking to 1 and back to 0. The test is labelled "end-to-end integration" but only verifies that DI works — every failure mode that happens *after* bean wiring is invisible.

Concretely, the five success criteria from plan 03-04:
1. "DashboardServer bean is wired" — tested.
2. "SkillRegistryAdapter replaces NoOpSkillRegistry" — tested.
3. "A user @Bean returning agent() is injectable as AgentRunner" — tested (only injection, not invocation).
4. "KoreActuatorEndpoint.health() returns status=UP" — tested.
5. "Spring context shutdown stops the DashboardServer cleanly" — **not tested**. No `@DirtiesContext` or explicit shutdown hook verification.

**Fix:** Add a sixth test that invokes the agent and asserts:

```kotlin
@Test
fun `user agent bean runs the full loop through auto-wired EventBus and AuditLog`() = runTest {
    val collected = mutableListOf<AgentEvent>()
    val job = launch { eventBus.subscribe().collect { collected += it } }
    yield()  // let collector subscribe

    val result = testAgent.run(AgentTask(id = UUID.randomUUID().toString(), input = "hello"))

    result.shouldBeInstanceOf<AgentResult.Success>()
    collected.filterIsInstance<AgentEvent.AgentStarted>() shouldHaveSize 1
    collected.filterIsInstance<AgentEvent.AgentCompleted>() shouldHaveSize 1
    job.cancel()
}
```

This catches the real bug class that bean-wiring tests miss: auto-configured beans that are wired but semantically incompatible (e.g. an EventBus subscriber that is registered after the first emission is lost).

## Low

### LO-01: AgentLoop.runLoop assumes history always contains a User message

**File:** `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt:96`

**Issue:** `val userMessage = history.first { it.role == ConversationMessage.Role.User }`. The current `run()` code path guarantees a User message is added before `runLoop` is called, so this never throws today. But `first { }` throwing `NoSuchElementException` is a silent precondition — a future refactor that adds a retry-from-checkpoint path (e.g. resuming from an audit log) might legitimately start the loop with only system messages and get a runtime crash instead of a compile error.

**Fix:** Either (a) change to `firstOrNull` and skill-inject only if non-null, or (b) document the precondition with `require(history.any { it.role == Role.User })` at the top of `runLoop`. Option (b) turns the implicit contract into an explicit one.

---

### LO-02: DashboardServer start() engine guard is not atomic

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt:91-102`

**Issue:** `if (engine.get() != null) return` is a check-then-act that is not safe under concurrent `start()` invocations. Two threads entering `start()` simultaneously can both observe `null`, both launch a Ktor engine, and both try to bind port 8090 — one succeeds, the other throws `BindException`. In practice Spring's `LifecycleProcessor` is single-threaded so this is unreachable from normal flows, but the code makes no assertion of that.

**Fix:** Use an atomic compare-and-set and construct the engine under the guard:

```kotlin
override fun start() {
    val current = engine.get()
    if (current != null) return
    val newEngine = embeddedServer(CIO, port = properties.port) { ... }
    if (!engine.compareAndSet(null, newEngine)) {
        // another thread beat us — no need to start
        return
    }
    observer.startCollecting()
    newEngine.start(wait = false)
}
```

Or wrap `start()` / `stop()` with `synchronized(this)`. Either is fine; the current code is not.

---

### LO-03: AgentState.currentTask is declared but never populated

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/AgentState.kt:15`
**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt:45-57`
**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt:54`

**Issue:** `AgentState` has a `currentTask: String = ""` field. The observer never sets it — `AgentStarted` carries only `agentId` and `taskId`, not task content, and the copy paths for `LLMCallCompleted` use `state?.copy(tokensUsed = ...)` which preserves the blank default. The fragment then renders `+state.currentTask.truncate(60)` as an empty cell in every row. The "Current Task" column is dead weight.

**Fix:** Either (a) drop the column from the fragment and delete the field, or (b) thread the task content through `AgentStarted` (but this is T-03-03 — never bring task content into the dashboard read path, so probably not). Option (a) is consistent with the rest of the privacy model.

---

### LO-04: PostgresAuditLogAdapter.queryRecentRuns does not validate limit parameter

**File:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:98-119`

**Issue:** `queryRecentRuns(limit: Int)` passes `limit` straight through to Exposed's `.limit(limit)` without validation. A caller (or a future Spring-MVC endpoint) passing `Int.MAX_VALUE`, `0`, or `-1` produces undefined behaviour: Exposed 1.0 accepts zero and returns an empty list, negative values behave differently per dialect, and `Int.MAX_VALUE` reintroduces the memory-pressure concern from ME-03.

**Fix:**

```kotlin
override suspend fun queryRecentRuns(limit: Int): List<AgentRunRecord> {
    require(limit in 1..1_000) { "queryRecentRuns limit must be in 1..1000, got $limit" }
    // ... existing query ...
}
```

The kore-dashboard caller uses `limit = 20` so the bound is not hit in the happy path.

---

### LO-05: resultBadge falls back to raw database string for unknown result types

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt:142`

**Issue:** The `else` branch of `resultBadge` renders the uppercased database value directly:

```kotlin
else -> "neutral" to normalized.uppercase()
```

kotlinx.html escapes the inserted text correctly, so this is not an XSS issue, but it does mean a typo in the DB (`"suces"`) or a future result type added to `AgentResult` without a fragment update will render the raw string in a user-facing badge. The fallback is documented ("rather than crashing the fragment") which is reasonable, but consider logging a warning the first time an unknown value is seen so the gap is discoverable.

**Fix:** Log (at `WARN` level, rate-limited) when the fallback branch is taken:

```kotlin
else -> {
    // Unknown result type — render but log so we can fix the mapping.
    if (unknownSeen.add(normalized)) {
        // static set; only log once per process per unknown value
        logger.warn("Unknown result type '$normalized' in resultBadge — add to the when expression.")
    }
    "neutral" to normalized.uppercase()
}
```

Alternatively, make the mapping exhaustive by deriving `resultType` from the `AgentResult` sealed class at the query layer (e.g. a `ResultTypeCode` enum) instead of passing strings through the AgentRunRecord DTO.

---

### LO-06: ToolResult.toJsonString is a hand-rolled serializer that will break on control characters

**File:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:183-186`

**Issue:** Pre-existing before Phase 3 (the file was modified but this function was not touched in the phase diff). Flagging because the serializer is on the write path that feeds the read queries this phase introduces:

```kotlin
private fun ToolResult.toJsonString(): String {
    val escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"content":"$escapedContent","isError":$isError}"""
}
```

This only escapes `\` and `"`, not the required JSON control characters (`\b`, `\f`, `\n`, `\r`, `\t`, or `\u0000-\u001F`). A tool that returns a newline-containing string (extremely common) produces invalid JSON that PostgreSQL stores as-is. The storage succeeds but any downstream consumer calling `JSON.parse` on `arguments` or `result` will fail.

**Fix:** Use `kotlinx.serialization` or Jackson instead of string concatenation. This module already has Jackson transitively via Spring Boot BOM.

Out of scope for Phase 3 but worth logging since it touches the write path for the new read queries.

## Nit

### NI-01: private Jackson mapper is not thread-safe by contract

**File:** `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt:27`

Jackson's `ObjectMapper` and `YAMLMapper` are documented as thread-safe **after configuration**, but should be configured once then treated as immutable. `SkillLoader` constructs a new instance per `SkillLoader` object; if multiple adapters are instantiated (tests, dev-tools reload) each one creates its own mapper. Consider a `companion object val mapper` that is shared across all `SkillLoader` instances.

---

### NI-02: Fragments.kt uses attributes["style"] for inline styling

**File:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt:149-167`

The TOTAL footer row uses inline styles (`attributes["style"] = "font-weight: 600;"`) on every `<td>`. Prefer a CSS class (`.kore-table-total td { font-weight: 600 }`) in `PageShell.kt`'s `koreEmbeddedCss` block to keep presentation concerns in one place.

---

### NI-03: Test file imports java.util.concurrent via lowercase package

**File:** `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt:202-223`

The test uses fully-qualified names like `dev.unityinflow.kore.core.port.AgentCostRecord(...)` inside the test body. The ktlint chain-method-continuation rule forced this in some cases, but it hurts readability. Add imports at the top of the file:

```kotlin
import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog
```

And delete the inline FQNs. Minor readability improvement.

---

## Pitfall Checklist Verification

| Pitfall | Honoured? | Evidence |
|---|---|---|
| 1 — Eager auto-config | ✓ | All conditional @Configuration inner classes use `@ConditionalOnClass(name=[...])` string form (KoreAutoConfiguration.kt lines 79, 92, 105, 129, 151, 168, 190, 214). |
| 2 — @ConditionalOnClass with class ref | ✓ | String form used throughout. |
| 3 — Ktor `wait=true` | ✓ | `DashboardServer.kt:100` uses `wait = false`. |
| 4 — kaml dependency | ✓ | `SkillLoader.kt` uses jackson-dataformat-yaml; no kaml anywhere. |
| 5 — YAML skill validation at runtime | ⚠ | Regex validation happens at PatternMatcher init (eager), but YAML syntax errors are not caught — see **ME-01**. |
| 6 — Observer started after server | ✓ | `DashboardServer.start()` calls `observer.startCollecting()` before `embeddedServer().start()`. |
| 7 — InMemoryAuditLog missing read methods | ✓ | Implemented at `InMemoryAuditLog.kt:36-39`, returning empty lists per D-27. |
| 8 — System role not supported | ✓ | `ConversationMessage.Role.System` exists as a sealed class case; `AgentLoop.kt:111` prepends it at position 0. Backend-side handling is not in review scope. |
| T-03-11 — active-agents DoS bound | ⚠ | Bound is enforced but eviction order is arbitrary — see **ME-02**. |
| T-03-13 — API keys in config | ✓ | `KoreProperties.apiKey` defaults to blank; README hero demo uses `${KORE_CLAUDE_API_KEY}` env var. |
| T-03-14 — dashboard port in tests | ✓ | Both `KoreAutoConfigurationTest` and `KoreIntegrationTest` set `kore.dashboard.enabled=false`. |
| T-03-03 — task content in dashboard | ✓ | `AgentRunRecord` projection excludes task_input; `PostgresAuditLogAdapter.queryRecentRuns` uses `.select(...)` instead of `.selectAll()`. |
| T-03-05 — actuator leaks sensitive data | ✓ | `KoreActuatorEndpoint.health()` exposes only status, count, backend class name, version string. |

## Hexagonal Boundary Verification

| Boundary | Direction | Honoured? | Evidence |
|---|---|---|---|
| kore-core → no adapter deps | — | ✓ | `kore-core/build.gradle.kts` lists `compileOnly("io.opentelemetry:opentelemetry-api:...")`; SkillRegistry port ships with `NoOpSkillRegistry` default. |
| kore-skills → kore-core only | — | ✓ | `kore-skills/build.gradle.kts` has `api(project(":kore-core"))` + jackson, nothing else kore-module. |
| kore-dashboard → no kore-spring | — | ✓ | `kore-dashboard/build.gradle.kts` has `compileOnly("org.springframework:spring-context:7.0.0")` for `SmartLifecycle` interface only; `DashboardProperties` is defined as an interface inside `DashboardServer` so the module does not leak `KoreProperties` back to kore-dashboard. `KoreDashboardPropertiesAdapter` lives in kore-spring and wraps the kore-spring properties one-way. |
| kore-spring → all optional kore modules | — | ✓ | All optional deps are `compileOnly(project(":kore-..."))`, `@ConditionalOnClass` at runtime. `compileOnly` transitive symbol redeclaration for `R2dbcDatabase` and `Tracer` is correct. |
| kore-storage → no kore-spring | — | ✓ | Unchanged in this phase; still only depends on kore-core and Exposed. |

Overall: dependency graph is acyclic and directional. The property-bag adapter pattern (`KoreDashboardPropertiesAdapter`) is a clean way to cross the kore-spring ↔ kore-dashboard boundary without pulling Spring into the side-car.

---

*Reviewed: 2026-04-14*
*Reviewer: Claude (gsd-code-reviewer)*
*Depth: standard*
