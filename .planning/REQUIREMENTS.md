# Requirements: kore-runtime — Milestone v0.0.2 "Hardening & Hierarchy"

**Defined:** 2026-06-12
**Core Value:** A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control.

## v0.0.2 Requirements

Requirements for this milestone. Each maps to roadmap phases.

### Budget Enforcement

- [ ] **BUDG-05**: Developer can enable real token-budget enforcement by adding the `kore-budget` dependency and setting `kore.budget.enabled=true` (Spring Boot auto-configured; `InMemoryBudgetEnforcer` remains the default when absent)
- [ ] **BUDG-06**: Agent run that hits its hard budget limit ends with `AgentResult.BudgetExceeded` — `BudgetHardLimitException` never escapes the `BudgetEnforcer` port (verified by a test that drives the adapter to the hard limit)
- [ ] **BUDG-07**: Concurrent agents have isolated budgets keyed by `AgentTask.id` (UUID), not agent name — no cross-agent budget interference or `withBudget` id collisions

### Hierarchical Agents

- [ ] **HIER-01**: Developer can declare child agents via `child { }` in the `agent { }` DSL — child runs as a tool call (spawn model) and its result feeds back into the parent loop as a `ToolResult`
- [ ] **HIER-02**: Cancelling a parent agent cancels all running child agents — structured concurrency, verified by a cancellation-propagation test
- [ ] **HIER-03**: Child spawning is bounded by a configurable `maxDepth` (default 5) — exceeding depth yields `ToolError`, never unbounded recursion
- [ ] **HIER-04**: Audit log records `parent_run_id` on child agent runs so run trees are traceable

### Observability

- [x] **OBSV-03**: Skill activation emits a `kore.skill.activate` OTel span correctly parented under the agent-run span (via `KoreTracer.withSpan`), with skill name/count/duration attributes
- [x] **OBSV-04**: Skill activation emits `AgentEvent.SkillActivated` on the event bus for metrics observers

### CI / Testing

- [x] **CI-01**: Developer can run kore-storage's 7 Testcontainers integration tests via a dedicated `./gradlew :kore-storage:integrationTest` task (tag-filtered, fails loudly if 0 tests execute)
- [x] **CI-02**: CI runs the integration tests on arc-runner-unityinflow with a Docker pre-flight check (`docker info`), asserting tests actually executed

## Future Requirements (v0.1.0)

Deferred to future release. Tracked but not in current roadmap.

### Budget Enforcement

- **BUDG-08**: Child budget slicing — `childBudget = parent * fraction` to prevent child cost compounding (Augment Code measured 4–15× in production)
- **BUDG-09**: `AgentEvent.BudgetWarning` emitted on soft-limit via EventBus

### Dashboard

- **DASH-01**: HTMX dashboard widget showing active child agent trees

### Hierarchical Agents

- **HIER-05**: Streaming child `AgentResult` as `Flow<LLMChunk>` back to parent

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Handoff model for multi-agent | Breaks the ReAct loop and severs the coroutine scope chain — spawn model is strictly superior for kore's architecture |
| Global shared budget across agents | Concurrent shared counter corrupts under real concurrency (arXiv 2606.04056: 30/30 overshoots) — per-agent isolation only |
| Cross-process budget coordination | Single-JVM scope for v0.0.x line |
| budget-breaker Spring Boot starter dependency | Not yet published — kore-budget gates auto-config on class presence of the core library only |
| Dynamic skill hot-reload | Not needed for hardening milestone |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| BUDG-05 | Phase 6 | Pending |
| BUDG-06 | Phase 6 | Pending |
| BUDG-07 | Phase 6 | Pending |
| HIER-01 | Phase 7 | Pending |
| HIER-02 | Phase 7 | Pending |
| HIER-03 | Phase 7 | Pending |
| HIER-04 | Phase 7 | Pending |
| OBSV-03 | Phase 5 | Complete (emission 05-02; KoreTracer support 05-04; parenting test 05-03) |
| OBSV-04 | Phase 5 | Complete (emission 05-02; observer reactions 05-03) |
| CI-01 | Phase 5 | Complete |
| CI-02 | Phase 5 | Complete |

**Coverage:**

- v0.0.2 requirements: 11 total
- Mapped to phases: 11 ✓
- Unmapped: 0

---
*Requirements defined: 2026-06-12*
*Last updated: 2026-06-12 after roadmap creation (Phases 5-7 mapped)*
