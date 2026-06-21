# Phase 6: Real Budget Enforcement - Context

**Gathered:** 2026-06-21
**Status:** Ready for planning

<domain>
## Phase Boundary

A new `kore-budget` module whose adapter implements the **existing**
`BudgetEnforcer` port using `io.github.unityinflow:budget-breaker` (Tool 05),
giving developers real hard-stop token-budget enforcement. It is Spring-Boot
auto-configured ON when the dependency is present and `kore.budget.enabled=true`;
`InMemoryBudgetEnforcer` remains the default otherwise so existing apps behave
identically (BUDG-05, BUDG-06, BUDG-07).

It does NOT change the `BudgetEnforcer` port, does NOT touch `AgentLoop`, and is
independent of Phase 5 (no shared files). Scope is the hard token-count stop only
— cost, rate, and soft-warning budgets are explicitly deferred.

</domain>

<decisions>
## Implementation Decisions

### Port stability (the anchor decision)
- **D-00:** The `BudgetEnforcer` port stays **byte-identical** — no new methods,
  no signature changes. The three existing methods (`recordUsage(agentId, usage)`,
  `checkBudget(agentId): Boolean`, `getUsage(agentId): TokenUsage`) are the entire
  contract the adapter implements. Both per-agent-name overrides (Tension 1) and
  run-end eviction (Tension 2) were deliberately dropped to preserve this — see
  D-01 and D-05. Rationale: zero `AgentLoop` ripple and no coordination cost with
  Phase 7, which also rebases on `AgentLoop`.

### Config surface
- **D-01:** **Single global hard token limit** via the existing
  `kore.budget.default-max-tokens` property (already on `KoreProperties.budget`,
  consumed today by the `InMemoryBudgetEnforcer` default bean). NO per-agent-name
  overrides this phase — the port only receives `agentId` (= `AgentTask.id` UUID)
  and `AgentTask` carries no agent name, so per-name overrides would require a port
  change or a side-channel registry, both rejected under D-00. Keep the
  `KoreProperties.budget` **shape extensible** so a future `agents.<name>.max-tokens`
  map can be added without a breaking change.

### Budget dimensions (scope)
- **D-02:** **Hard token-count stop only.** budget-breaker's cost (USD), rate
  limits, and soft-warning thresholds are NOT wired in Phase 6. Defer to a later
  phase — see Deferred Ideas.

### Exceed behavior
- **D-03:** Hitting the hard limit ends the run with `AgentResult.BudgetExceeded`
  and nothing more — **no soft-warning event** (no `AgentEvent.BudgetWarning`, no
  threshold knob) this phase. Matches the success criteria exactly.

### Adapter bridge (poll port ↔ throw library)
- **D-04:** **Internal-tally pre-check + catch-on-record.** The adapter keeps
  per-`agentId` accumulated spend. `checkBudget(agentId)` returns whether tallied
  spend is still under the configured limit (so the loop stops *before* overshooting).
  `recordUsage(agentId, usage)` feeds budget-breaker and **catches
  `BudgetHardLimitException` internally so it NEVER escapes the port** (BUDG-06).
  Budgets are keyed by `agentId` (= `AgentTask.id`, a per-run UUID) — this is
  already how `AgentLoop` calls the port, so concurrent agents are isolated and
  budget-breaker `withBudget` ids never collide (BUDG-07).

### Budget lifecycle
- **D-05:** **No eviction** — the adapter mirrors `InMemoryBudgetEnforcer`'s
  accepted tradeoff (per-`agentId` state lives for the process lifetime, bounded by
  running-agent count, T-03-04). The port has no run-end hook and D-00 forbids
  adding one. Long-lived-process accumulation is an accepted, documented limitation;
  revisit if/when the port gains a lifecycle method.

### Module & auto-configuration
- **D-06:** New `kore-budget` Gradle module (added to `settings.gradle.kts`)
  provides a `BudgetEnforcer` `@Bean` gated by `@ConditionalOnClass(name=[budget-breaker class])`
  **and** `@ConditionalOnProperty("kore.budget.enabled", havingValue="true")`. It
  wins over the kore-spring `InMemoryBudgetEnforcer` default via the existing
  `@ConditionalOnMissingBean(BudgetEnforcer::class)` ordering — so absence of the
  dependency or `enabled=false` leaves the InMemory default untouched (BUDG-05).
  Mirror the conditional-bean conventions already in `KoreAutoConfiguration.kt`.
  (Open: whether the auto-config class lives in `kore-budget` or `kore-spring` —
  Claude's discretion / research; the gating semantics above are locked.)

### Claude's Discretion
- Exact Gradle coordinates/version of `io.github.unityinflow:budget-breaker` and
  whether kore-budget needs Spring on its compile classpath (vs. a Spring-free
  adapter + auto-config registered via `kore-spring`).
- Where the auto-config class physically lives (D-06 open clause).
- The precise internal tally structure in the adapter (e.g. `ConcurrentHashMap`
  mirroring the stub) and how it reconciles its tally with budget-breaker's own
  accounting so the two never disagree.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & Roadmap
- `.planning/REQUIREMENTS.md` §Real Budget Enforcement — BUDG-05, BUDG-06, BUDG-07 acceptance wording
- `.planning/ROADMAP.md` §"Phase 6: Real Budget Enforcement" — the 3 Success Criteria

### The port and the stub to replace (BUDG-05/06/07)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/BudgetEnforcer.kt` — the 3-method port the adapter implements; stays byte-identical (D-00)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/internal/InMemoryBudgetEnforcer.kt` — the default stub; the adapter mirrors its keying (per-agentId) and no-eviction tradeoff (D-05)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt` §runLoop (lines ~157–186) — the only caller: `checkBudget(agentId)` before each LLM call, `recordUsage(agentId, callUsage)` after; returns `AgentResult.BudgetExceeded` (BUDG-06). NOT modified this phase.
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentTask.kt` — `data class AgentTask(id, input, metadata)`; `id` is the per-run UUID used as the budget key (BUDG-07); note: no `name` field (drove D-01)

### Auto-config pattern to mirror (BUDG-05)
- `kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt` — `@ConditionalOnMissingBean(BudgetEnforcer::class)` default bean wiring `InMemoryBudgetEnforcer(properties.budget.defaultMaxTokens)`; the new kore-budget bean must win over this
- `KoreProperties` (kore-spring) — existing `budget.defaultMaxTokens`; reused as the single global limit (D-01)
- `settings.gradle.kts` — module registration (`include(...)`) for the new `kore-budget` module

### External library (RESEARCH — see headline question below)
- `io.github.unityinflow:budget-breaker` (Tool 05, GitHub: UnityInFlow/budget-breaker) — the throw-based budget library. NOT in this repo; researcher must read its published API.

### Cross-cutting constraints
- `08-kore-runtime/CLAUDE.md` §Constraints — no `var`, no `!!` without comment, coroutines only, Gradle Kotlin DSL, JUnit 5 + Kotest assertions, MockK, group `io.github.unityinflow`
- `.planning/phases/03-*/` and `.planning/phases/04-*/` CONTEXT — prior Spring auto-config + event-bus decisions (D-24 low-cardinality, conditional-bean conventions)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `KoreProperties.budget.defaultMaxTokens` already exists and already feeds the
  InMemory default bean — the adapter reuses the *same* property as its single
  global limit (D-01). No new config property required for the MVP.
- `KoreAutoConfiguration.kt` already demonstrates the exact
  `@ConditionalOnMissingBean(BudgetEnforcer::class)` + string-form
  `@ConditionalOnClass` conventions kore-budget must follow (D-06).
- `InMemoryBudgetEnforcer` is the reference implementation for keying
  (per-`agentId` `ConcurrentHashMap`) and the accepted no-eviction tradeoff (D-05).

### Established Patterns
- The port is a **poll** contract (`checkBudget` before, `recordUsage` after);
  budget-breaker is a **throw** contract (`withBudget(id){}` raising
  `BudgetHardLimitException`). The adapter is the impedance-matcher (D-04).
- `agentId` passed to the port IS `AgentTask.id` (`val agentId = task.id` in
  `AgentLoop.run`) — a fresh UUID per run, which is what gives BUDG-07 isolation
  for free without any port change.

### Integration Points
- kore-budget connects to the system ONLY through the `BudgetEnforcer` `@Bean`
  resolved by Spring; `AgentLoop` is unaware which implementation it holds.
- No database, no event-bus, no network surface added.

</code_context>

<specifics>
## Specific Ideas

- **Headline research question (for gsd-phase-researcher):** does
  `io.github.unityinflow:budget-breaker` expose a **non-throwing pre-check**
  (e.g. "remaining" / "would-exceed" query)? If yes, `checkBudget` (D-04) maps to
  it directly and cleanly. If the only signal is the thrown `BudgetHardLimitException`,
  the adapter must maintain its own tally to implement `checkBudget` and only learns
  the hard limit was hit by catching the exception in `recordUsage`. Research must
  pin down the `withBudget` signature, the exception type/package, and any
  per-budget reset/scope semantics before planning.
- BUDG-06's test must drive the adapter to the hard limit and assert the run ends
  in `AgentResult.BudgetExceeded` with `BudgetHardLimitException` NOT propagating —
  a direct adapter test (MockK or a real budget-breaker budget set to a tiny limit).
- BUDG-07's test must run two agents concurrently with distinct `AgentTask.id`s and
  assert no cross-interference / no `withBudget` id collision.

</specifics>

<deferred>
## Deferred Ideas

- **Per-agent-name (and per-model) budget overrides** — needs the port to carry
  agent identity (or a side-channel registry); deferred to keep the port unchanged.
  Config shape kept extensible (D-01) so it slots in later.
- **Cost (USD) budgets** — needs per-model pricing tables; deferred (D-02).
- **Rate limits** from budget-breaker — deferred (D-02).
- **Soft-warning thresholds + `AgentEvent.BudgetWarning` + `kore.budget.warn-at`** —
  deferred (D-03); revisit when dashboard/alerting wants pre-emptive signals.
- **Run-end budget eviction / a `release(agentId)` port lifecycle hook** — deferred
  (D-05); revisit if long-lived-process accumulation becomes a real problem.

None of these are in scope for Phase 6.

</deferred>

---

*Phase: 06-real-budget-enforcement*
*Context gathered: 2026-06-21 via /gsd-discuss-phase (deep-dive)*
