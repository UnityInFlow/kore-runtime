---
phase: 06-real-budget-enforcement
reviewed: 2026-06-21T00:00:00Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - kore-budget/build.gradle.kts
  - kore-budget/src/main/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapter.kt
  - kore-budget/src/test/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapterTest.kt
  - kore-spring/build.gradle.kts
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreProperties.kt
  - kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/BudgetBreakerAutoConfigurationTest.kt
findings:
  critical: 0
  warning: 4
  info: 3
  total: 7
status: issues_found
---

# Phase 6: Code Review Report

**Reviewed:** 2026-06-21
**Depth:** standard
**Files Reviewed:** 7
**Status:** issues_found

## Summary

Reviewed the real budget-enforcement implementation: the `BudgetBreakerAdapter`
that bridges kore's `BudgetEnforcer` port to budget-breaker (Tool 05)
`TokenTracker`, the Spring Boot auto-configuration that wires it in, the
`KoreProperties` binding, and both test suites.

The adapter's core logic is sound and the auto-configuration triple-gate
(`@ConditionalOnClass` + `@ConditionalOnProperty` + `@ConditionalOnMissingBean`)
correctly mirrors the existing Kafka/RabbitMQ pattern. `checkBudget` boundary
semantics are consistent with `InMemoryBudgetEnforcer` (both stop at exactly the
limit). No Critical defects found.

However there is a genuine silent-truncation correctness bug in `getUsage`
(Long → Int narrowing), a dependency-version pin that conflicts with both the
project version-catalog convention and the current published budget-breaker
artifact, and a test gap: nothing exercises the documented `BudgetException`
catch path or `getUsage` near the Int boundary.

To verify the dependency claims I inspected the budget-breaker `0.0.1` jar
bytecode in the Gradle cache and the `0.1.0` sources in `.m2`; the `TokenTracker`
/ `AgentBudget` API the adapter calls is present in both, so the pin compiles —
it is a convention/staleness issue, not a build break.

## Warnings

### WR-01: `getUsage` silently truncates Long token counts to Int (data corruption under high usage)

**File:** `kore-budget/src/main/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapter.kt:63-66`

**Issue:** `TokenTracker.promptTokens` and `completionTokens` are `Long`
(`AtomicLong`-backed). `getUsage` narrows them with `.toInt()`:

```kotlin
trackers[agentId]?.let {
    TokenUsage(it.promptTokens.toInt(), it.completionTokens.toInt())
}
```

`Kotlin`'s `Long.toInt()` truncates the high 32 bits with no error. Once an
agent accumulates more than `Int.MAX_VALUE` (~2.147B) prompt or completion
tokens, `getUsage` returns a wrong — and likely **negative** — value. This is
reachable in practice precisely in the configuration this phase targets:
budget enforcement *disabled* via `defaultMaxTokens = Long.MAX_VALUE` (the very
value the concurrency test uses on line 72 of the test) over a long-lived
process. With a real hard limit the tracker would stop the agent first, but
`getUsage` is a reporting API that callers (dashboard cost summary, audit log)
may read independently of `checkBudget`, so the corruption surfaces even when
enforcement is on but the limit is set above 2.1B tokens.

`InMemoryBudgetEnforcer` does not have this bug because it stores `TokenUsage`
(Int) from the start and accumulates in Int — so the two enforcer
implementations also disagree on overflow behavior, breaking port substitutability.

**Fix:** Keep the values as Long for as long as possible, and clamp rather than
wrap when forced into the Int-typed `TokenUsage`. Better: widen `TokenUsage` to
Long (the proper fix, since budget-breaker is Long-native), or at minimum:

```kotlin
override suspend fun getUsage(agentId: String): TokenUsage =
    trackers[agentId]?.let {
        TokenUsage(
            it.promptTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            it.completionTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    } ?: TokenUsage(0, 0)
```

`coerceAtMost` removes the negative-wrap landmine; a follow-up to make
`TokenUsage` Long-typed end-to-end is the real fix.

### WR-02: budget-breaker dependency hardcoded to stale `0.0.1`, bypassing the version catalog

**File:** `kore-budget/build.gradle.kts:11`

**Issue:**

```kotlin
implementation("io.github.unityinflow:budget-breaker:0.0.1")
```

Two problems:
1. The coordinate is a raw string literal, not a version-catalog alias. Every
   other dependency in this same file uses `libs.*` (`libs.coroutines.core`,
   `libs.junit5`, etc.). The project CLAUDE.md mandates Gradle version catalogs
   (`gradle/libs.versions.toml`), and `grep` confirms there is **no** `budget`
   entry in the catalog — so this is the lone hardcoded, uncatalogued version in
   the module.
2. The pinned `0.0.1` is stale. The locally published artifact is `0.1.0`
   (`~/.m2/.../budget-breaker/0.1.0/`) and the ecosystem CLAUDE.md lists
   budget-breaker as shipped to Maven Central. Pinning `0.0.1` means kore-budget
   ships against an old API/behavior of its core dependency. (Verified the
   `0.0.1` jar still exposes the `TokenTracker.add(long,long)` /
   `isAboveHardLimit()` surface the adapter uses, so it compiles — this is a
   staleness/convention defect, not a compile break.)

**Fix:** Add to `gradle/libs.versions.toml`:

```toml
[versions]
budget-breaker = "0.1.0"
[libraries]
budget-breaker = { module = "io.github.unityinflow:budget-breaker", version.ref = "budget-breaker" }
```

and reference `implementation(libs.budget.breaker)` in `build.gradle.kts`.

### WR-03: `model` constructor parameter is dead configuration — never reachable from Spring

**File:** `kore-budget/.../BudgetBreakerAdapter.kt:30` and `kore-spring/.../KoreAutoConfiguration.kt:190-193`

**Issue:** `BudgetBreakerAdapter` exposes a `model: String = "kore-agent"`
constructor parameter, documented as "the budget-breaker model label." But the
only production caller — `BudgetBreakerAutoConfiguration.budgetBreakerAdapter`
— constructs the adapter with **only** `defaultHardLimitTokens`, so `model`
always takes the hardcoded `"kore-agent"` default. `KoreProperties.BudgetProperties`
(lines 112-115) has no `model` field either. The parameter is therefore
unconfigurable in the Spring path and, per the adapter's own KDoc, is
"informational only; cost/soft limits are unused" — meaning it influences
nothing at all. This is dead surface area that implies a configurability that
does not exist.

**Fix:** Either delete the `model` parameter (and the `model =` line in
`trackerFor`), or wire it through `BudgetProperties.model` and the `@Bean`
method so it is actually configurable. Removing it is simpler given it is
documented as having no behavioral effect.

### WR-04: No test covers the documented `BudgetException` catch path or the `getUsage` truncation boundary

**File:** `kore-budget/src/test/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapterTest.kt` (whole file)

**Issue:** The adapter's `recordUsage` wraps `tracker.add(...)` in a
`try/catch (_: BudgetException)` block (lines 52-58) described as a load-bearing
"no budget-breaker exception escapes the port" guarantee (BUDG-06). No test
forces `add` to throw, so the catch is uncovered and its contract is unverified
— and since `add` provably cannot throw against the pinned `0.0.1` jar, the
block is effectively untested dead code whose justification rests entirely on a
comment. Separately, no test drives `getUsage` anywhere near the Int boundary
(WR-01), so the truncation bug passes CI silently. The CLAUDE.md testing
requirement ("at least 3 passing and 3 failing cases", ">80% on core logic")
is not met for these branches.

**Fix:** Add a boundary test that records usage above `Int.MAX_VALUE` against a
`Long.MAX_VALUE`-limit adapter and asserts `getUsage` does not return a negative
value. If the `BudgetException` catch is retained, add a test using a stubbed
`TokenTracker` (MockK is already on the test classpath) that throws from `add`
and asserts `recordUsage` does not propagate; otherwise delete the catch and
document that `add` is non-throwing.

## Info

### IN-01: `BudgetException` catch is effectively unreachable dead code

**File:** `kore-budget/.../BudgetBreakerAdapter.kt:52-58`

**Issue:** `TokenTracker.add` is two `AtomicLong.addAndGet` calls and cannot
throw `BudgetException` (confirmed against both `0.0.1` bytecode and `0.1.0`
source). The catch is defensive-by-comment only. This is acceptable as a
forward-compatibility guard, but readers should know it never fires today.

**Fix:** Keep if desired for API-evolution safety, but consider a one-line
comment stating the current `add` impl is non-throwing, or remove to avoid
implying a real failure mode (see WR-04).

### IN-02: Adapter requires `softLimitTokens == hardLimitTokens` workaround that depends on a budget-breaker invariant

**File:** `kore-budget/.../BudgetBreakerAdapter.kt:42-44`

**Issue:** The adapter sets `softLimitTokens = defaultHardLimitTokens` to satisfy
`AgentBudget`'s `require(softLimitTokens <= hardLimitTokens)` init check, since
kore does not use soft limits. This couples the adapter to budget-breaker's
internal validation rule: if a future budget-breaker release makes
`softLimitTokens` strictly less-than, or changes the default, this construction
breaks. It is correct today (verified against the `init` block in
`AgentBudget.kt`) but is a fragile cross-module assumption.

**Fix:** No change required now. If budget-breaker exposes a "no soft limit"
sentinel or a hard-only constructor in a later version, prefer that.

### IN-03: Repeated fully-qualified inline class references reduce readability

**File:** `kore-spring/.../KoreAutoConfiguration.kt:98-99, 111-112, 135-136, 148-149, 190-191`

**Issue:** The `@Bean` bodies use fully-qualified names
(`io.github.unityinflow.kore.budget.BudgetBreakerAdapter`, etc.) because the
optional modules are `compileOnly`. This is the correct and intentional pattern
(documented in the file header and consistent with the Kafka/RabbitMQ blocks),
but the long inline FQNs hurt readability. Purely stylistic — no functional
concern.

**Fix:** Optional: import the types normally (they are on the compile classpath
via `compileOnly`, so imports resolve at compile time) to shorten the bodies.
Leave as-is if the team prefers FQNs to signal the compileOnly boundary.

---

_Reviewed: 2026-06-21_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
