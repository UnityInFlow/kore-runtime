# Pitfalls Research

**Domain:** Kotlin/JVM coroutine agent runtime — adding budget enforcement, hierarchical agents, OTel skill-activation spans, and Testcontainers CI to an existing shipped system (kore v0.0.1)
**Researched:** 2026-06-12
**Confidence:** HIGH — all pitfalls verified against actual kore-core/budget-breaker source code and known behaviour of the JVM/coroutine/OTel stack

---

## Critical Pitfalls

---

### Pitfall 1: budget-breaker `withBudget` wraps the WRONG scope — `BudgetHardLimitException` escapes the agent loop as an unhandled exception

**What goes wrong:**
`BudgetCircuitBreaker.withBudget` throws `BudgetHardLimitException` (a subclass of `BudgetException extends Exception`) when the hard limit is exceeded. The adapter wrapping `AgentLoop.run` inside `withBudget` must catch `BudgetHardLimitException` and translate it into `AgentResult.BudgetExceeded`. If the adapter places `withBudget` at the wrong layer — outside the `try/catch` block in `AgentLoop.runLoop` — the exception propagates up through `AgentRunner.scope.async { }` and surfaces as a failed `Deferred`, not a sealed result. Callers expecting `AgentResult` never receive `BudgetExceeded`; they get an exception on `deferred.await()`. The INVARIANT comment in `AgentLoop` ("run NEVER throws") is silently broken.

**Why it happens:**
The existing `AgentLoop` calls `budgetEnforcer.checkBudget(agentId)` as a boolean check before each LLM call. The `InMemoryBudgetEnforcer` never throws. The `BudgetCircuitBreaker` adapter changes the contract: `recordUsage` and `trackCall` can now throw. Developers writing the adapter may wire `withBudget` as a decorator around `AgentLoop.run()` at the `AgentRunner` layer without accounting for the exception boundary.

**How to avoid:**
The `BudgetBreakerAdapter` must catch `BudgetHardLimitException` inside its `recordUsage` (or inside the adapter's internal wrapper) and signal budget exhaustion through the existing `BudgetEnforcer.checkBudget` boolean contract. The adapter keeps `checkBudget` returning `false` once the tracker crosses the hard limit, letting the existing agent loop `return AgentResult.BudgetExceeded(...)` path fire normally. Never let `BudgetHardLimitException` escape the adapter — it is an implementation detail of the budget-breaker library, not a contract of the `BudgetEnforcer` port.

Concrete adapter strategy:
1. Maintain a per-agent `AtomicBoolean` `hardLimitReached` flag inside the adapter.
2. `recordUsage`: calls `tracker.add(...)`, then sets the flag if `tracker.isAboveHardLimit()`. Does NOT throw.
3. `checkBudget`: returns `!hardLimitReached.get()`.
4. The `BudgetCircuitBreaker.withBudget` block is NOT used — the adapter talks to `TokenTracker` directly (it is `internal` to budget-breaker — this is a coupling risk; see Pitfall 2).

**Warning signs:**
- Unit test: `AgentLoop.run()` with a budget-breaker adapter configured at 1 token — result should be `AgentResult.BudgetExceeded`, not a thrown exception.
- If `AgentRunner.run(task).await()` throws a `BudgetHardLimitException` rather than returning a sealed result, the boundary is wrong.

**Phase to address:** Phase 1 (budget-breaker adapter implementation)

---

### Pitfall 2: `BudgetCircuitBreaker` enforces "one concurrent `withBudget` per agentId" — hierarchical agents sharing an agentId deadlock or crash

**What goes wrong:**
`BudgetCircuitBreaker.withBudget` calls `activeTrackers.putIfAbsent(agentId, tracker)` and throws `IllegalArgumentException` ("Agent X is already running inside withBudget") if the slot is occupied. A parent agent and a child agent using the SAME `agentId` for budget tracking would hit this guard. Even with different agentIds, if the parent's `withBudget` block spans the child's entire lifecycle, the child's `withBudget` call with a DIFFERENT id works fine — but the parent's budget is only tracking parent LLM calls, not child LLM calls. The parent budget and child budget are siloed: a parent with a 100k token hard limit could launch 10 children, each with their own 100k limit, spending 10x the intended budget.

**Why it happens:**
The budget-breaker `BudgetCircuitBreaker` was designed for independent agent runs, not for hierarchical agents. The `TokenTracker` is per-`withBudget` invocation. There is no parent/child budget aggregation.

**How to avoid:**
Two separate concerns must be addressed:
1. Use distinct agentIds for parent and each child (e.g. `parentId + "-child-0"`). Never share an agentId across concurrent `withBudget` calls.
2. Add a total-budget guard at the parent level: the parent's `BudgetEnforcerAdapter` tracks not just its own token usage but accumulated child usage. Implement this by having child agents report their `TokenUsage` result back to the parent's `BudgetEnforcer` after completion (via `recordUsage` on the parent enforcer). The `checkBudget` on the parent then considers aggregated usage.

Alternative simpler approach for v0.0.2: do NOT use `withBudget` scope for hierarchical agents — use the `TokenTracker` low-level API directly (the adapter owns the tracker). The `withBudget` scope DSL is for standalone agent runs; the port-adapter pattern should wrap the tracker directly.

**Warning signs:**
- `IllegalArgumentException: Agent 'X' is already running inside withBudget` in tests or production.
- Budget cost reports show parent used far fewer tokens than expected (children's tokens not aggregated).

**Phase to address:** Phase 1 (hierarchical agents + budget-breaker adapter together)

---

### Pitfall 3: `SupervisorJob` on `AgentRunner` means a failing child agent does NOT cancel siblings — but cancelling the parent DOES cancel all children only if children are launched in the parent's scope

**What goes wrong:**
`AgentRunner` and `ObservableAgentRunner` both use `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. This is correct for independent agents: one failure does not bring down others. For hierarchical agents, the parent agent's `AgentLoop.run()` must launch child agents in a `coroutineScope { }` or `supervisorScope { }` child scope, NOT in a new `CoroutineScope(SupervisorJob())`. Constructing a NEW `CoroutineScope` inside the agent loop severs the parent-child relationship: cancelling the parent does not cancel the children.

The existing `AgentLoop` uses `coroutineScope { toolCalls.map { async { ... } }.awaitAll() }` for parallel tool dispatch — this pattern is correct. The same pattern must be applied for child agent spawning. A common mistake when adding hierarchical support is to do:

```kotlin
// WRONG: severs structured concurrency
val childScope = CoroutineScope(SupervisorJob())
childScope.launch { childLoop.run(childTask) }
```

instead of:

```kotlin
// CORRECT: child is a child of the caller's coroutine scope
supervisorScope {
    val childDeferred = async { childLoop.run(childTask) }
    // ...
}
```

**Why it happens:**
Developers see `AgentRunner` using `SupervisorJob` and copy that pattern into the hierarchical agent spawning code, not realising `AgentRunner` is the top-level runner bean, not something to replicate inside the loop.

**How to avoid:**
Child agents spawned by a parent agent MUST be launched via `supervisorScope { async { childLoop.run(...) } }` inside the parent's `AgentLoop.run()` suspend function. The child coroutines are then children of the parent's coroutine job. Cancelling the parent (via `AgentRunner.shutdown()` or a timeout) cascades to all children.

Write a test: start a parent agent that spawns two children, cancel the parent via `deferred.cancel()`, assert both children terminate (via `isActive` or `deferred.isCancelled`) within 100ms.

**Warning signs:**
- After calling `agentRunner.shutdown()`, Micrometer's active-agent gauge does not drop to zero.
- Parent agent completes but child agent coroutines are still running (visible in coroutine debugger or via `scope.coroutineContext.job.children.count()`).
- `OTel` traces show child agent spans without a parent span — they are orphaned root spans.

**Phase to address:** Phase 1 (hierarchical agents)

---

### Pitfall 4: `AgentLoop` skill-activation span uses raw `spanBuilder().startSpan()` without `asContextElement()` — the span is unparented and context is lost on suspension

**What goes wrong:**
The current `AgentLoop.runLoop` implementation (line 97) creates the skill-activation span as:
```kotlin
val span = tracer?.spanBuilder("kore.skill.activate")?.startSpan()
try { skillRegistry.activateFor(...) } finally { span?.end() }
```

This has two defects for v0.0.2:
1. The span is not parented: `spanBuilder` uses `Context.current()` implicitly only if `setParent(Context.current())` is called explicitly. Without this call, the span appears as a root span, disconnected from the parent `kore.agent.run` span from `ObservableAgentRunner`.
2. The span context is not stored in the coroutine context element: `skillRegistry.activateFor` is a `suspend fun`. If it suspends and resumes on a different thread (coroutine dispatcher), the OTel `ThreadLocal` context no longer holds the span. Any OTel instrumentation inside `activateFor` will see no parent span.

The OBSV-03 requirement says "OTel span on skill activation, wired to the `SkillActivated` event." The current implementation in `EventBusSpanObserver` has `// OBSV-03 stub` and no `SkillActivated` event exists in `AgentEvent`. There are two alternative approaches and picking the wrong one creates the defect above.

**Why it happens:**
The span stub in `AgentLoop` was written in Phase 3 before `KoreTracer.withSpan` was available. It uses the raw OTel API directly, bypassing the `withContext(span.storeInContext(Context.current()).asContextElement())` pattern established in `KoreTracer.withSpan`.

**How to avoid:**
OBSV-03 implementation must use the `KoreTracer.withSpan` helper, not raw `spanBuilder`:
```kotlin
tracer?.withSpan(
    name = KoreSpans.SKILL_ACTIVATE,
    attrs = mapOf(KoreAttrs.AGENT_ID to agentId, "kore.skill.count" to activatedCount)
) { _ ->
    skillRegistry.activateFor(taskContent, availableTools)
}
```
This ensures: (a) parent context is inherited from `Context.current()` via `setParent(Context.current())` inside `withSpan`, (b) the span is stored as a `ContextElement` so it survives suspension.

Regarding the `SkillActivated` event: if OBSV-03 is implemented via `AgentLoop` directly calling `KoreTracer.withSpan`, there is NO need to add a `SkillActivated` event to `AgentEvent` — the span is managed inline. If instead the approach is to emit a `SkillActivated` event and let `EventBusSpanObserver` open/close the span (like `LLMCallStarted`/`LLMCallCompleted`), then `AgentEvent` requires a new sealed subclass. Adding a new subclass to `AgentEvent` IS a binary-compatible change for consumers (they receive it as `AgentEvent`, the `when` expression in `EventBusSpanObserver` has an `else -> Unit` branch). Choose the inline `withSpan` approach to avoid changing the serialized event wire format.

**Warning signs:**
- OTel trace viewer: `kore.skill.activate` span has no parent span (appears as a root span alongside `kore.agent.run` rather than as a child).
- `span.spanContext().traceId` differs from the parent agent run's traceId.
- Span duration is 0ms or not recorded (span ended before `activateFor` completes due to the `try/finally` wrapping only the call, not awaiting its result — but since `activateFor` is `suspend` and the `finally` runs after it returns, duration should be correct; the parentage issue is the real defect).

**Phase to address:** Phase 1 (OBSV-03 wiring)

---

### Pitfall 5: `integrationTest` Gradle source set wired incorrectly — tests silently never run

**What goes wrong:**
Adding an `integrationTest` source set in `kore-storage/build.gradle.kts` requires wiring five things correctly. Missing any one causes the tests to silently pass (empty test suite) rather than fail:

1. Source set declaration: `sourceSets.create("integrationTest") { ... }` — if omitted, `src/integrationTest/kotlin` files are ignored.
2. Configuration inheritance: `integrationTestImplementation.extendsFrom(testImplementation)` — if omitted, Testcontainers classes are not on the integration test classpath.
3. Task registration: `val integrationTest by tasks.registering(Test::class) { ... }` — if omitted, no task exists to run.
4. `useJUnitPlatform { includeTags("integration") }` inside the new task — if the existing `tasks.test` block has `excludeTags("integration")` but the new task has NO tag filter, it runs ALL tests including unit tests, defeating the purpose; if the new task has `includeTags("integration")` but the tests are not tagged `@Tag("integration")`, zero tests run.
5. The `check` task must depend on `integrationTest` or it will not run during `./gradlew build` — OR the CI must call `integrationTest` explicitly, which is the correct pattern since integration tests need Docker.

The current `kore-storage` tests are tagged `@Tag("integration")` (confirmed in `PostgresAuditLogAdapterTest`). The existing `tasks.test` block uses `excludeTags("integration")`. The new task must use `includeTags("integration")`.

**Why it happens:**
Gradle source sets are not automatically test-execution tasks. The source set, classpath configuration, task, and CI step are four independent wiring points that are easy to partially complete. A partially wired setup compiles successfully but produces an empty test run with exit code 0.

**How to avoid:**
Complete wiring in `kore-storage/build.gradle.kts`:
```kotlin
val integrationTest by tasks.registering(Test::class) {
    description = "Runs Testcontainers integration tests against a real PostgreSQL container."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}
```

Then move the `@Tag("integration")` tests to `src/integrationTest/kotlin` OR keep them in `src/test/kotlin` and use tag-based filtering (the simpler option for v0.0.2 given only 7 tests). The simpler approach: keep all Testcontainers tests in `src/test/kotlin`, register a new `integrationTest` task that runs only the `integration` tag, and do NOT move files.

Add a CI step in `.github/workflows/ci.yml` that explicitly calls `./gradlew :kore-storage:integrationTest` and requires Docker socket availability (see Pitfall 6).

**Warning signs:**
- `./gradlew :kore-storage:integrationTest` exits 0 with "0 tests executed" — tests are being silently skipped, not filtered.
- The task appears in `./gradlew tasks --group verification` but shows no test results after running.
- `BUILD SUCCESSFUL` with `Tests were not run` note in the output.

**Phase to address:** Phase 1 (Testcontainers CI wiring)

---

### Pitfall 6: Docker socket not available on `arc-runner-unityinflow` — Testcontainers containers fail to start silently

**What goes wrong:**
Testcontainers requires a Docker daemon accessible at `/var/run/docker.sock` (or via `DOCKER_HOST`). Self-hosted arc-runner-unityinflow (Hetzner, Linux x64) runners may or may not have Docker available depending on how the runner was provisioned. Three failure modes:
1. Docker not installed: Testcontainers throws `DockerNotAvailableException` and the test suite errors (not fails) — CI job exits non-zero with a configuration error, not a test failure.
2. Docker installed but socket permission denied: `Permission denied: /var/run/docker.sock` — same outcome as above.
3. Docker available but no `postgres:16-alpine` image cached: first run pulls ~80MB image, causing the test to fail with a startup timeout if `withStartupTimeout(Duration.ofMinutes(3))` is too aggressive for a cold pull on the runner.

The `PostgresAuditLogAdapterTest` uses `waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))` with a 3-minute startup timeout. This is appropriate for a warm Docker but may not be enough for a cold pull on a loaded CI runner.

**Why it happens:**
Local development works (Docker Desktop). The arc-runner provisioning is done separately from the CI workflow definition. Docker availability is assumed but not verified.

**How to avoid:**
1. Verify Docker is available on arc-runner runners: `docker --version` and `docker info` in a one-off CI job before adding the integration test step.
2. Add a pre-flight check in the CI step:
   ```yaml
   - name: Check Docker
     run: docker info
   ```
3. Pre-pull the PostgreSQL image as a separate step so the 3-minute timeout is not consumed by the pull:
   ```yaml
   - name: Pull PostgreSQL image
     run: docker pull postgres:16-alpine
   ```
4. Increase `withStartupTimeout` to 5 minutes for CI (or use `Testcontainers.disabled()` conditional on `CI` env var absence for speed in local dev).
5. If Docker is not available on arc-runner, the integration test CI step must run on a dedicated Docker-capable runner or use Docker-in-Docker (`dind`) — document which runner label supports Docker.

**Warning signs:**
- CI log shows `Can't connect to Docker daemon at unix:///var/run/docker.sock` or `Permission denied`.
- `ContainerLaunchException: Startup check failed` in the Testcontainers log.
- The integration test CI step exits 137 (OOM killed) on a constrained runner — the Postgres container consumes ~256MB RAM.

**Phase to address:** Phase 1 (CI wiring, before integration test task is merged)

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Keep `BudgetEnforcer.checkBudget` as boolean poll (one call per LLM iteration) rather than wrapping the entire agent run in `withBudget` | Simpler adapter, no scope refactoring | Budget-breaker's aggregated cost reporting (`BudgetReport`, `getAllReports()`) is not surfaced — kore users can't see per-agent USD cost | Acceptable for v0.0.2; expose `BudgetReport` via a separate `BudgetEnforcer.getReport(agentId)` optional API in v0.1.0 |
| Keep `SkillActivated` event out of `AgentEvent` (use inline `withSpan` instead of event-driven span) | No wire-format change, no `@Serializable` update | `EventBusSpanObserver` can't correlate skill activation timing with LLM call timing post-hoc from event streams (Kafka consumers) | Acceptable for v0.0.2; add `SkillActivated` event with skill name + prompt count in v0.1.0 |
| Keep Testcontainers tests in `src/test/kotlin` with tag-based filtering instead of a true `integrationTest` source set | Zero file movement, minimal Gradle change | Integration tests are not isolated from unit tests in IDE — running "all tests" in IntelliJ includes Testcontainers tests and requires Docker locally | Acceptable for v0.0.2; migrate to separate source set in v0.1.0 if the test suite grows beyond 10 integration tests |
| Child agent budget is tracked independently (no parent aggregation) | Simpler adapter, no cross-agent state | Parent agent can overspend if it launches many children without a total budget cap | Acceptable for v0.0.2 if documented; add parent-aggregate budget tracking in v0.1.0 |

---

## Integration Gotchas

Common mistakes when connecting to external services or cross-module boundaries.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| budget-breaker `BudgetCircuitBreaker` | Call `withBudget` and let `BudgetHardLimitException` escape the `BudgetEnforcer` port | Catch `BudgetHardLimitException` inside the adapter; surface hard limit as `checkBudget()` returning `false` |
| budget-breaker `TokenTracker` (internal class) | Try to instantiate or extend `TokenTracker` directly from the adapter | `TokenTracker` is in the `io.github.unityinflow.budget` package but has no `internal` modifier — it is effectively public. However, it is an implementation detail. The correct integration surface is `BudgetCircuitBreaker.withBudget` + `BudgetScope.trackCall`. If `withBudget` scope semantics are incompatible, prefer using `BudgetCircuitBreaker` as a reporting sink only and the `TokenTracker` read APIs for observability |
| `AgentLoop` receiving `KoreTracer?` for OBSV-03 | Create span with raw `tracer.spanBuilder(name).startSpan()` without context propagation | Use `koreTracer.withSpan(name) { ... }` which calls `setParent(Context.current())` and wraps with `asContextElement()` |
| `EventBusSpanObserver` adding `SkillActivated` span | Open a span in `SkillActivated` event handler using `Context.current()` — this runs in the event bus consumer coroutine, which has a DIFFERENT OTel context than the agent loop coroutine | If event-driven approach is chosen, the `SkillActivated` event must carry the serialized `SpanContext` (traceId + spanId) so the observer can create a child span with the correct remote parent via `Span.wrap(spanContext)` |
| Gradle `integrationTest` task + `excludeTags` on `tasks.test` | Add `includeTags("integration")` to new task but forget to verify the existing `excludeTags("integration")` in `tasks.test` is not accidentally also filtering out unit tests | Confirm `tasks.test` runs 0 integration tests and `integrationTest` task runs all 7; add assertions in a CI step that checks test counts |
| `kore-storage` `compileOnly` vs `testImplementation` for `serialization-core` | Add `integrationTest` task without adding `serialization-core` to the integration test classpath — `NoClassDefFoundError` at runtime for `AgentResult` deserialization | Extend the integration test configuration: `integrationTestImplementation(libs.serialization.core)` |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Hierarchical agent tree with no depth limit — unbounded recursive agent spawning | Memory exhaustion from `ConversationMessage` history accumulation per agent; coroutine count grows exponentially | Add `maxDepth: Int` parameter to the hierarchical agent spawning API; enforce at the parent before launching a child | At depth 3+ with 3 children per level (27+ agents) on a 4GB JVM |
| Parent agent and all children sharing the same `InProcessEventBus` `SharedFlow` — burst of events from N concurrent children overwhelms the buffer | `SharedFlow` drops events silently (DROP_OLDEST overflow strategy) causing missing OTel spans and metrics | Ensure the event bus is configured with `extraBufferCapacity = 1024 * N_max_agents` or use a dedicated event bus per agent tree | At 50+ concurrent agents all completing simultaneously |
| `BudgetCircuitBreaker.getAllReports()` called on every LLM call to get live snapshot for dashboard | O(active_agents) map copy on every call; contention on the `ConcurrentHashMap` iterators | Call `getAllReports()` on a polling schedule (every 5s) rather than per LLM call; cache the snapshot | At 100+ concurrent agents with 1+ LLM calls per second each |
| `findProvider(toolName)` in `AgentLoop` calls `provider.listTools()` on every tool call — N * M suspend calls | Slow tool dispatch latency; `listTools()` network call per tool lookup for MCP providers | Cache the tool list at the start of the loop iteration (already done via `toolDefs` val); ensure MCP `listTools()` is cached in the MCP client, not called on every tool dispatch | With MCP providers returning 20+ tools and 5+ tool calls per loop iteration |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Budget-breaker adapter exposes `BudgetReport` (includes `estimatedCostUsd`) through an unauthenticated endpoint | Cost data leakage; competitor intelligence | Gate any `BudgetReport` API behind Spring Security or Actuator security; do not expose via the HTMX dashboard without authentication |
| Child agent receives the parent's full tool list including privileged tools (e.g., file write, database access) | Privilege escalation via LLM prompt injection in child agent | Pass an explicit `allowedTools: List<String>` to child agent configuration; child agents should receive a scoped-down tool set |
| `agentId` for child agents is predictable (e.g., `parentId + "-child-0"`) | An external caller constructing a child agentId could inject budget records under a predictable key | Use `UUID.randomUUID().toString()` for child agentIds; the parent tracks children by their `Deferred` references, not by ID |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Budget-breaker adapter:** `BudgetEnforcer` implementation compiles and passes unit tests — but verify `AgentResult.BudgetExceeded` is returned (not a thrown exception) when the hard limit is reached. Run `AgentLoopTest` with the real adapter, not just `InMemoryBudgetEnforcer`.
- [ ] **Hierarchical agents:** Parent-child cancellation test exists — but verify it tests the PARENT cancelling the CHILD, not just `coroutineScope` cancellation on child failure. The test must call `parentDeferred.cancel()` and assert `childDeferred.isCancelled` is true.
- [ ] **OBSV-03 skill span:** The `kore.skill.activate` span appears in the OTel trace viewer — but verify it is a CHILD of the `kore.agent.run` span (same traceId, correct parentSpanId). A root span with the right name is not OBSV-03 complete.
- [ ] **OBSV-03 span attributes:** The span records `kore.agent.id` and the count of activated skills — not just an empty span. Check span attributes in the `InMemorySpanExporter` in tests.
- [ ] **`integrationTest` task:** `./gradlew :kore-storage:integrationTest` exits 0 and reports "7 tests executed" — not "0 tests executed". Confirm by running with `--info` to see test class names.
- [ ] **CI integration test step:** The new CI step in `ci.yml` has `./gradlew :kore-storage:integrationTest` and the workflow was actually triggered and passed — not just added to the YAML. Confirm via a passing workflow run with the step visible in the Actions log.
- [ ] **Binary compatibility:** Any new parameter added to `AgentLoop` constructor must have a default value so existing consumers that build `AgentLoop(...)` directly do not get a compilation error. Verify with `./gradlew :kore-core:apiDump` or binary-compatibility-validator if the plugin is configured.
- [ ] **`compileOnly` propagation:** The `integrationTest` configuration in `kore-storage` must include `libs.serialization.core` (same reason as `testImplementation` in `kore-observability`). Verify by running the integration tests with a fresh Gradle cache.

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| `BudgetHardLimitException` escaping as unhandled exception in production | MEDIUM | Add a catch in `AgentRunner.run()` as a safety net: `catch(e: BudgetException) { AgentResult.BudgetExceeded(spent = TokenUsage(0,0), limit = 0) }`. Deploy hotfix. Then fix the adapter boundary properly. |
| Hierarchical agents creating orphaned coroutines (broken structured concurrency) | HIGH | No runtime fix — requires a code change. Identify orphaned coroutines via coroutine debugger dump (`kotlinx.coroutines.debug`). Count by `CoroutineScope.coroutineContext.job.children.count()`. Fix by replacing `CoroutineScope(SupervisorJob())` with `supervisorScope { }` in spawning code. |
| OBSV-03 skill span appearing as root span (no parent) | LOW | Replace raw `spanBuilder` call with `koreTracer.withSpan(...)` in `AgentLoop`. No data loss — traces are just missing the parent link. |
| `integrationTest` task running 0 tests | LOW | Check tag filter: `./gradlew :kore-storage:integrationTest --info` to see which tests were discovered. Verify `@Tag("integration")` annotation is present on test classes. Verify `includeTags("integration")` is set on the task. |
| Docker not available on CI runner | LOW | Add `dind` service container to the CI step or provision Docker on the runner. Short-term: skip integration tests in CI with `./gradlew :kore-storage:integrationTest -Dskip.integration=true` and run them manually. |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| `BudgetHardLimitException` escaping `BudgetEnforcer` port (P1) | Phase 1 — budget-breaker adapter | Unit test: `AgentLoop.run()` returns `AgentResult.BudgetExceeded`, not thrown exception, at hard limit |
| Concurrent `withBudget` collision on same agentId (P2) | Phase 1 — budget-breaker adapter + hierarchical agent design | Unit test: parent + child agent with different IDs both complete without `IllegalArgumentException` |
| `SupervisorJob` severing parent-child cancellation (P3) | Phase 1 — hierarchical agents | Unit test: cancel parent deferred, assert child deferred is cancelled within 100ms |
| Skill-activation span unparented / context lost on suspension (P4) | Phase 1 — OBSV-03 | Unit test with `InMemorySpanExporter`: verify `kore.skill.activate` span has parentSpanId matching `kore.agent.run` span |
| `integrationTest` task silently running 0 tests (P5) | Phase 1 — Testcontainers Gradle wiring | `./gradlew :kore-storage:integrationTest --info` shows "7 tests executed"; verify in CI log |
| Docker not available on arc-runner (P6) | Phase 1 — CI step, before merging | Pre-flight `docker info` step in the integration test CI job; must pass before integration test step runs |

---

## Sources

- Actual `AgentLoop.kt` source (kore v0.0.1, lines 97-106): raw `spanBuilder` without `setParent` or `asContextElement`
- Actual `BudgetCircuitBreaker.kt` source (budget-breaker v0.0.1): `putIfAbsent` guard + `BudgetHardLimitException` throw path
- Actual `kore-storage/build.gradle.kts`: `excludeTags("integration")` with no `integrationTest` task registered
- Actual `PostgresAuditLogAdapterTest.kt`: `@Tag("integration")` + `@Testcontainers` + `postgres:16-alpine`
- Actual `KoreTracer.kt`: `withSpan` helper correctly uses `setParent(Context.current())` + `asContextElement()` — OBSV-03 must use this, not raw `spanBuilder`
- Actual `EventBusSpanObserver.kt`: `// OBSV-03 stub` comment confirms no `SkillActivated` handling exists
- Actual `AgentEvent.kt`: confirms no `SkillActivated` subclass in the sealed class
- Actual `ObservableAgentRunner.kt` + `AgentRunner.kt`: both use `SupervisorJob()` at the runner scope level — correct for independent agents, not for hierarchical spawning inside the loop
- Actual `.github/workflows/ci.yml`: no `integrationTest` step present — confirms gap
- `.planning/debug/knowledge-base.md`: `compileOnly` serialization propagation defect (NoClassDefFoundError pattern) — same pattern applies to `integrationTest` classpath
- Kotlin coroutines documentation — structured concurrency: `supervisorScope` vs `CoroutineScope(SupervisorJob())` semantics
- OpenTelemetry Kotlin extension (`opentelemetry-extension-kotlin`): `asContextElement()` requirement for context propagation across suspension points

---
*Pitfalls research for: kore-runtime v0.0.2 Hardening & Hierarchy*
*Researched: 2026-06-12*
