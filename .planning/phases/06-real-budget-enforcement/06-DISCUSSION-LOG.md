# Phase 6: Real Budget Enforcement — Discussion Log

**Date:** 2026-06-21
**Mode:** discuss (deep-dive)

> Human-reference record of the discussion. Not consumed by downstream agents — see `06-CONTEXT.md` for the canonical decisions.

## Areas selected for discussion

All four offered gray areas: Config surface, Budget dimensions, Exceed behavior, Budget lifecycle / poll-vs-throw bridge.

## Round 1 — headline choices

| Area | Options presented | Selected |
|------|-------------------|----------|
| Config surface | global default + per-agent overrides · single global limit · per-model limits | **Global default + per-agent overrides** (later superseded — see Round 2) |
| Dimensions | hard token only · token + USD cost · everything | **Hard token-count stop only** |
| Exceed behavior | hard-stop only · soft-warning at ~80% · soft-warning + configurable threshold | **Hard-stop only this phase** |
| Bridge | internal-tally pre-check + catch · wrap in withBudget · lock contract, defer mechanics | **Internal-tally pre-check, catch on record** |

## Round 2 — deep-dive (two code-grounded tensions surfaced)

Reading `BudgetEnforcer.kt` + `AgentTask.kt` revealed the port only ever receives `agentId` (= `AgentTask.id` UUID); `AgentTask` has no agent name, and the port has no run-end hook. This put two Round-1 leanings in conflict with the roadmap's "keep the existing port" lock.

| Tension | Options presented | Selected | Effect |
|---------|-------------------|----------|--------|
| Per-agent-name overrides need identity the port can't see | single global limit (keep port) · side-channel registry · evolve the port | **Single global limit this phase** | Supersedes Round-1 "per-agent overrides"; port stays byte-identical |
| Budgets keyed by task.id never evict | mirror InMemory (no eviction) · add `release()` hook | **Mirror InMemory — no eviction** | Port stays byte-identical |

**Net effect:** both deep-dive choices preserve the `BudgetEnforcer` port unchanged → zero `AgentLoop` ripple, no Phase 7 coordination. Captured as D-00 (port stability anchor) in CONTEXT.md.

## Decisions captured → CONTEXT.md

D-00 port byte-identical · D-01 single global `kore.budget.default-max-tokens` (shape extensible) · D-02 hard token only · D-03 hard-stop only, no soft event · D-04 internal-tally pre-check + catch `BudgetHardLimitException` · D-05 no eviction · D-06 `kore-budget` module, `@ConditionalOnClass`+`@ConditionalOnProperty`, wins via `@ConditionalOnMissingBean`.

## Deferred (noted, not lost)

Per-agent/per-model overrides · USD cost budgets · rate limits · soft-warning thresholds + `BudgetWarning` event + `warn-at` knob · run-end eviction / `release()` lifecycle hook.

## Open for research (not a user decision)

Whether `io.github.unityinflow:budget-breaker` exposes a non-throwing pre-check vs. throw-only (`BudgetHardLimitException`) — determines how cleanly D-04's `checkBudget` maps. Researcher must pin the `withBudget` signature, exception package, and scope/reset semantics.
