---
phase: 05-ci-baseline-skill-observability
reviewed: 2026-06-20T00:00:00Z
depth: standard
files_reviewed: 17
files_reviewed_list:
  - .github/workflows/ci.yml
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentEvent.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/SkillRegistry.kt
  - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentEventSerializationTest.kt
  - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentLoopSkillTest.kt
  - kore-dashboard/src/main/kotlin/io/github/unityinflow/kore/dashboard/EventBusDashboardObserver.kt
  - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusMetricsObserver.kt
  - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusSpanObserver.kt
  - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/KoreMetrics.kt
  - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/KoreTracer.kt
  - kore-observability/src/test/kotlin/io/github/unityinflow/kore/observability/EventBusMetricsObserverTest.kt
  - kore-observability/src/test/kotlin/io/github/unityinflow/kore/observability/KoreTracerTest.kt
  - kore-observability/src/test/kotlin/io/github/unityinflow/kore/observability/ObservableAgentRunnerTest.kt
  - kore-skills/src/main/kotlin/io/github/unityinflow/kore/skills/SkillRegistryAdapter.kt
  - kore-skills/src/test/kotlin/io/github/unityinflow/kore/skills/SkillRegistryAdapterTest.kt
  - kore-storage/build.gradle.kts
findings:
  critical: 0
  warning: 6
  info: 5
  total: 11
status: issues_found
---

# Phase 05: Code Review Report

**Reviewed:** 2026-06-20
**Depth:** standard
**Files Reviewed:** 17
**Status:** issues_found

## Summary

Reviewed the Phase 05 CI baseline + skill observability change set: the agent loop's
skill-activation span/event path, the three event-bus observers (dashboard, metrics,
spans), the metrics/tracer facades, the skills adapter, and the storage build with its
zero-test guard and CI workflow.

The deliberate design decisions called out in the prompt all check out and are
implemented as documented:

- **Span-always / event-on-match asymmetry** (AgentLoop.kt:105-149) is correct: the
  span is built whenever a tracer is present (including 0 matches), while
  `SkillActivated` is emitted only when `activated.isNotEmpty()`. Tests 6/8/9 prove both
  arms.
- **Span survives an exception from `activateFor`** (AgentLoop.kt:121-130): the `finally`
  block reads `activatedHolder[0].orEmpty()` so attributes default to count=0/names=[]
  and `end()` always fires. Test 10 confirms the span finishes even when `activateFor`
  throws. The `arrayOfNulls(1)` holder is a `val`, so it stays CLAUDE.md no-var compliant.
- **Three observers' explicit `SkillActivated` branch before `else -> Unit`**
  (EventBusDashboardObserver.kt:90, EventBusSpanObserver.kt:107, and the active
  increment branch in EventBusMetricsObserver.kt:80) are consistent with the documented
  intent: dashboard/span no-op, metrics increments per name + records duration once.
- **Gradle zero-test guard via `TestListener`** (kore-storage/build.gradle.kts:66-94)
  correctly avoids deprecated `afterSuite {}` and uses an `AtomicInteger` held in a `val`.
- **Bus event excludes prompts** (AgentEvent.kt:77-83, SkillRegistry.kt KDoc): only
  `skillNames` cross the bus; `ActivatedSkill.prompt` never does.
- **CI** uses self-hosted runners (`arc-runner-unityinflow`, `orangepi`) — no
  `ubuntu-latest`.

The findings below are mostly robustness/consistency concerns. The most material are the
gauge/map drift on cancellation (WR-01) and the duplicated, divergent duration
measurement feeding the span vs. the event (WR-02).

## Warnings

### WR-01: Cancellation path skips `AgentCompleted`, permanently drifting the active gauge and leaking dashboard map entries

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:75-85`
**Issue:** `run()` re-throws `CancellationException` (line 78) from inside the `try`
expression. Because the re-throw escapes before the `.also { ... }` block (lines 81-84)
runs, `auditLog.recordAgentRun` and `eventBus.emit(AgentEvent.AgentCompleted(...))` are
**never** executed when an agent is cancelled. Downstream observers key all their cleanup
and gauge-decrement logic on `AgentCompleted`:
- `EventBusMetricsObserver` increments `agentsActive` on `AgentStarted` (line 56) and only
  decrements on `AgentCompleted` (line 61). A cancelled agent therefore leaves
  `kore.agents.active` permanently incremented — the gauge drifts upward for every
  cancellation and never recovers.
- `EventBusDashboardObserver` only removes the agent from `activeAgents` on
  `AgentCompleted` (line 77). A cancelled agent stays "RUNNING" forever in the dashboard
  snapshot (until evicted by the capacity guard).
- `EventBusSpanObserver`'s leak guard (lines 94-105) also runs only on `AgentCompleted`,
  so open LLM/tool spans for a cancelled agent are never closed by the bus path.

The bounded-map eviction guards prevent *unbounded* growth, but they do not fix the gauge
drift (no eviction touches `agentsActive`) and they cause arbitrary eviction of *other*
live agents once at capacity. CancellationException is a normal occurrence under
structured concurrency (timeouts, scope cancellation), so this is not an edge case.
**Fix:** Emit a terminal event on the cancellation path before re-throwing so observers
can decrement/clean up. For example:
```kotlin
return try {
    runLoop(agentId, history, toolDefs, accumulatedUsage)
} catch (e: CancellationException) {
    auditLog.recordAgentRun(agentId, task, AgentResult.Cancelled(e.message ?: "cancelled"))
    eventBus.emit(AgentEvent.AgentCompleted(agentId, AgentResult.Cancelled(e.message ?: "cancelled")))
    throw e
} catch (e: Throwable) {
    AgentResult.LLMError(backend = llmBackend.name, cause = e)
}.also { result ->
    auditLog.recordAgentRun(agentId, task, result)
    eventBus.emit(AgentEvent.AgentCompleted(agentId = agentId, result = result))
}
```
(Note: emitting from a cancelled coroutine requires care — use `withContext(NonCancellable)`
around the emit, or have observers also decrement on a dedicated cancellation signal.)

### WR-02: Skill-activation duration is measured twice and the span and bus event report different values

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:122,141`
**Issue:** `durMs` is computed independently in two places from the same `startNanos`:
- Line 122 (inside `finally`) feeds the `kore.skill.duration_ms` span attribute.
- Line 141 (after building the System message) feeds `AgentEvent.SkillActivated.durationMs`.

The line-141 measurement happens *after* the `finally` block ended the span and after the
`history.add(0, ...)`/`joinToString` work, so the bus event's `durationMs` is always
`>=` the span's `duration_ms` and includes unrelated message-assembly time. Two
observability sinks reporting different durations for "the same" activation pass is a
correctness/consistency defect — `KoreMetrics.skillActivationDuration` (recorded from the
event in `EventBusMetricsObserver.kt:89`) and the span percentiles will not agree.
**Fix:** Compute the duration once and reuse it. Capture it in the `finally` (or just
before the span ends) and reuse the same value for the event:
```kotlin
val durMs: Long
...
} finally {
    durMs = (System.nanoTime() - startNanos) / 1_000_000
    ...span?.apply { setAttribute(..., durMs); end() }
}
...
eventBus.emit(AgentEvent.SkillActivated(agentId, activated.map { it.name }, durationMs = durMs))
```
(`durMs` must be hoisted/initialized to satisfy definite-assignment; alternatively store it
in the existing single-element holder pattern.)

### WR-03: `var accumulatedUsage` in `run()` is never reassigned — CLAUDE.md no-var violation and dead mutability

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:60`
**Issue:** `var accumulatedUsage = TokenUsage(0, 0)` is declared in `run()` but never
reassigned there — it is only read once when passed to `runLoop(...)` at line 76. The
actual accumulation happens on the separate `var accumulatedUsage` inside `runLoop`
(line 93, which is legitimately mutated). The project constraint is explicit: "No `var` —
always `val`." The `run()`-level `var` is both a constraint violation and misleading (it
implies mutation that never occurs). `lintKotlin`/ktlint will not catch this (it is not a
formatting rule), so it can slip through CI.
**Fix:** Change line 60 to `val accumulatedUsage = TokenUsage(0, 0)`.

### WR-04: `findProvider` re-invokes `listTools()` on every tool call, ignoring the already-built `toolDefs`

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:281-284`
**Issue:** `findProvider` calls `provider.listTools()` for every provider on every tool
dispatch (line 283), even though `run()` already materialized the full tool list into
`toolDefs` (lines 63-68). Beyond redundant work, this is a *correctness* risk if any
`ToolProvider.listTools()` is non-deterministic, performs I/O, or has side effects (e.g. a
provider that lists tools from a remote MCP server): the set the LLM was offered
(`toolDefs`) can diverge from the set used for routing, so a tool the model was told
exists may fail to resolve, or a tool may resolve to a provider that no longer advertises
it. Routing should be based on the snapshot the LLM actually saw.
**Fix:** Build a `Map<String, ToolProvider>` (or `Map<toolName, ToolProvider>`) once at the
top of `run()` from the same enumeration that produced `toolDefs`, and look up against
that snapshot instead of re-listing per call.

### WR-05: `ToolError` discards the original tool exception's stack trace

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:235-241,253-257`
**Issue:** When a tool throws, the per-tool catch (lines 235-241) keeps only
`e.message` in `ToolResult.content` and drops the `Throwable`. Later, the first errored
result is converted to `AgentResult.ToolError(... cause = RuntimeException(errorResult.content))`
(lines 254-257) — a brand-new `RuntimeException` carrying only the message string. The
original exception type and stack trace are lost, which undermines debuggability of tool
failures (the very thing `cause: Throwable` on `ToolError` exists to preserve). For
tools that fail with `message == null`, the content becomes the literal `"Tool error"`,
further erasing the signal.
**Fix:** Carry the real `Throwable` through. Either add an optional cause to `ToolResult`,
or distinguish the "tool returned isError" case from the "tool threw" case and propagate
the captured exception into `ToolError(cause = e)` instead of synthesizing a new one.

### WR-06: `BudgetExceeded.limit` reports `config.maxTokens`, not the enforcer's actual budget

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:153-157`
**Issue:** The budget gate is `budgetEnforcer.checkBudget(agentId)` (line 153), but the
returned `BudgetExceeded(limit = config.maxTokens.toLong())` reports `LLMConfig.maxTokens`
(default 4096) as the limit. The `BudgetEnforcer` is the authority on the actual budget;
`maxTokens` is a per-call generation cap, a semantically different quantity. When the two
differ (the common case — a budget enforcer typically tracks a run/session budget, not a
single-call max), the `BudgetExceeded` result reports a misleading limit to callers,
dashboards, and audit logs. `spent` comes from the enforcer (`getUsage`) while `limit`
comes from config, so the reported pair can be internally inconsistent (e.g. spent=200,
limit=4096 when the real budget was 150).
**Fix:** Expose the configured limit from `BudgetEnforcer` (e.g. a `limit(agentId)` accessor)
and report that, so `spent` and `limit` come from the same source of truth.

## Info

### IN-01: Duplicated skill-activation constant strings across modules with only KDoc to keep them in sync

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:292,125-127`
and `kore-observability/.../KoreTracer.kt:18,42-44`
**Issue:** The span name `"kore.skill.activate"` and the attribute keys
`kore.skill.names/count/duration_ms` are hard-coded as literals in `AgentLoop` and again as
constants (`KoreSpans.SKILL_ACTIVATE`, `KoreAttrs.SKILL_*`) in `KoreTracer`. The only thing
keeping them aligned is KDoc plus the `KoreTracerTest` assertion at lines 115-119, which
checks the `KoreAttrs` values against literals but does **not** import or compare against the
`AgentLoop` copies (kore-core cannot depend on kore-observability). If someone edits the
`AgentLoop` literals, no test fails. This is an accepted architectural trade-off, but the
"single source of truth" claim in the KDoc is aspirational, not enforced.
**Fix:** Add a test in a module that can see both (e.g. an integration test in
kore-observability that already drives a real `AgentLoop`, like
`ObservableAgentRunnerTest`) asserting the emitted span name/attribute keys equal the
`KoreAttrs`/`KoreSpans` constants — closing the drift gap without a compile-time dependency.

### IN-02: `EventBusSpanObserver` parents every LLM/tool span under `Context.current()`, which is empty on the bus collector coroutine

**File:** `kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusSpanObserver.kt:50-54,72-80`
**Issue:** Spans are created with `.setParent(Context.current())`, but the observer runs in a
detached `scope.launch { eventBus.subscribe().collect { ... } }` coroutine that has no
agent-run OTel context propagated into it. Unlike the in-process skill span (which is
created inside `AgentLoop` under the runner's context), these bus-driven LLM/tool spans will
almost always be **root** spans (orphaned from `kore.agent.run`), defeating the documented
"3-level hierarchy" (class KDoc lines 13-15). None of the reviewed tests assert parentage
for these bus-driven spans, so the gap is untested.
**Fix:** Propagate the agent-run context to the bus-driven spans (e.g. carry a serialized
trace context on the events, or look up the open agent span by `agentId`). At minimum, add
a test asserting LLM-call spans are parented under the agent-run span and document the
single-JVM limitation if true parentage is deferred.

### IN-03: Eviction-then-insert is not atomic; concurrent `AgentStarted` events can exceed `maxTrackedAgents`

**File:** `kore-dashboard/.../EventBusDashboardObserver.kt:60-73` and
`kore-observability/.../EventBusMetricsObserver.kt:51-56`
**Issue:** The capacity check (`size >= maxTrackedAgents`), the eviction, and the
`map[id] = ...` insert are three separate operations on the `ConcurrentHashMap`. The KDoc
implies a hard bound, but because the collector is a single coroutine the bound is only
honored if all `AgentStarted` events are processed sequentially (which they are today,
since each observer collects on one coroutine). If the collection model ever changes to
concurrent dispatch, the size can transiently exceed the cap. Low risk under the current
single-collector design; flag so the invariant is not silently broken later.
**Fix:** Either document that the bound relies on single-coroutine collection, or use an
atomic compute/merge that bounds within a single map operation.

### IN-04: `else -> Unit` branches make the exhaustive-`when` intent non-enforceable for future `AgentEvent` subclasses

**File:** `EventBusDashboardObserver.kt:92`, `EventBusMetricsObserver.kt:94`,
`EventBusSpanObserver.kt:111`
**Issue:** Each observer pairs an explicit `SkillActivated -> Unit` branch (good, for
discoverability) with a trailing `else -> Unit`. The `else` means that if a *new*
`AgentEvent` subclass is added later, the compiler will not flag these `when` blocks as
non-exhaustive, so a new event type silently gets ignored by all three observers. Given the
sealed hierarchy, an exhaustive `when` (no `else`, all branches listed) would turn future
additions into compile errors at exactly the sites that must decide how to handle them.
**Fix:** Drop `else -> Unit` and list every `AgentEvent` subclass explicitly (the no-op ones
mapped to `Unit`). The sealed class makes this safe and future-proof.

### IN-05: `SkillRegistryAdapter` swallows malformed-regex / loader errors at construction is not visible here

**File:** `kore-skills/src/main/kotlin/io/github/unityinflow/kore/skills/SkillRegistryAdapter.kt:24-31`
**Issue:** Skills and their `PatternMatcher`s are built eagerly in the constructor
(`loadAll()` then `associate { PatternMatcher(...) }`). If a skill YAML contains an invalid
regex in `task_matches`, `PatternMatcher` will presumably throw at construction, which would
propagate out of the `SkillRegistryAdapter` constructor and fail agent startup hard. That may
be intentional (fail-fast), but the behavior is undocumented and there is no test for the
malformed-pattern case among Tests 5-11. Construction-time exceptions also bypass the
`activateFor`-throws path that `AgentLoop` Test 10 covers.
**Fix:** Add a test asserting the intended behavior for an invalid `task_matches` regex
(fail-fast at construction vs. skip-the-skill), and document it in the adapter KDoc.

---

_Reviewed: 2026-06-20_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
