# Roadmap: kore-runtime

## Milestones

- ✅ **v0.0.1 Initial MVP** — Phases 1-4 (shipped 2026-04-26)
- 🚧 **v0.0.2 Hardening & Hierarchy** — Phases 5-7 (in progress)

## Phases

<details>
<summary>✅ v0.0.1 Initial MVP (Phases 1-4) — SHIPPED 2026-04-26</summary>

- [x] Phase 1: Core Runtime (7/7 plans) — completed 2026-04-11
- [x] Phase 2: Observability & Storage (3/3 plans) — completed 2026-04-13
- [x] Phase 3: Skills, Spring & Dashboard (5/5 plans) — completed 2026-04-14
- [x] Phase 4: Event Bus & Publishing (6/6 plans) — completed 2026-04-15

Full milestone details: [milestones/v0.0.1-ROADMAP.md](milestones/v0.0.1-ROADMAP.md)

</details>

### 🚧 v0.0.2 Hardening & Hierarchy (In Progress)

**Milestone Goal:** Close every item deferred at v0.0.1's close — real budget enforcement, hierarchical agents, full observability coverage, and integration-test CI — so the runtime is production-trustworthy before the v0.1.0 feature push.

- [x] **Phase 5: CI Baseline & Skill Observability** - Integration tests run in CI and skill activations are fully observable (span + event)
- [x] **Phase 6: Real Budget Enforcement** - budget-breaker adapter delivers actual hard-stop token budgets with per-agent isolation (completed 2026-06-21)
- [x] **Phase 7: Hierarchical Agents** - Parent agents spawn children via `child { }` with structured-concurrency cancellation and traceable run trees (5/5 criteria verified; integration UAT passed against real PostgreSQL) (completed 2026-06-22)

## Phase Details

### Phase 5: CI Baseline & Skill Observability

**Goal**: kore-storage's Testcontainers integration tests run in CI, and skill activations emit both an OTel span and an event-bus event — closing the last gaps in CI correctness and the span hierarchy
**Depends on**: Phase 4 (v0.0.1 complete)
**Requirements**: CI-01, CI-02, OBSV-03, OBSV-04
**Success Criteria** (what must be TRUE):

  1. Developer can run `./gradlew :kore-storage:integrationTest` and watch the tagged Testcontainers tests (13 `@Test` methods across 3 `@Tag("integration")` classes) execute against real PostgreSQL — the task fails loudly if 0 tests run
  2. CI runs the integration tests on arc-runner-unityinflow with a `docker info` pre-flight check, and the job asserts tests actually executed (no silent 0-test pass)
  3. A skill activation produces a `kore.skill.activate` OTel span correctly parented under the agent-run span, carrying skill name/count/duration attributes (visible in any OTel backend)
  4. A skill activation emits `AgentEvent.SkillActivated` on the event bus, observable by metrics observers (e.g., `EventBusSpanObserver` / `EventBusMetricsObserver`)

**Plans**: 4 plans (2 waves)
Plans:

- [x] 05-01-PLAN.md — integrationTest Gradle task (fail-loud zero-test guard) + integration-test CI job with docker pre-flight (CI-01, CI-02) [wave 1]
- [x] 05-02-PLAN.md — breaking SkillRegistry port → List<ActivatedSkill> + AgentLoop kore.skill.activate span attrs + AgentEvent.SkillActivated emission (OBSV-03/04 emission side) [wave 1]
- [x] 05-04-PLAN.md — KoreAttrs skill key constants + KoreTracer.withSpan string-array attribute branch (OBSV-03 support side) [wave 1] ✅ 2026-06-20 (1/1 tasks)
- [x] 05-03-PLAN.md — observer SkillActivated branches (metrics counter/duration, span no-op, dashboard) + span-parenting test through ObservableAgentRunner (OBSV-03/04) [wave 2, depends on 05-02] ✅ 2026-06-20 (2/2 tasks)

### Phase 6: Real Budget Enforcement

**Goal**: Developers get actual hard-stop token-budget enforcement by adding the new `kore-budget` module backed by `io.github.unityinflow:budget-breaker` — replacing the InMemoryBudgetEnforcer stub behind the existing `BudgetEnforcer` port
**Depends on**: Phase 4 (v0.0.1 complete) — independent of Phase 5, no shared files
**Requirements**: BUDG-05, BUDG-06, BUDG-07
**Success Criteria** (what must be TRUE):

  1. Developer can add the `kore-budget` dependency and set `kore.budget.enabled=true` to get real budget-breaker enforcement auto-configured; without the dependency, `InMemoryBudgetEnforcer` remains the default and existing apps behave identically
  2. An agent run that hits its hard budget limit ends with `AgentResult.BudgetExceeded` — `BudgetHardLimitException` never escapes the `BudgetEnforcer` port (proven by a test that drives the adapter to the hard limit)
  3. Concurrent agents have isolated budgets keyed by `AgentTask.id` (UUID) — two agents running simultaneously never interfere with each other's budgets or collide on `withBudget` ids

**Plans**: 2 plans (2 waves)
Plans:

- [x] 06-01-PLAN.md — kore-budget module + BudgetBreakerAdapter (TokenTracker-backed) + adapter/concurrency tests (BUDG-06, BUDG-07) [wave 1]
- [x] 06-02-PLAN.md — kore-spring BudgetBreakerAutoConfiguration triple-gate + KoreProperties.enabled flag + 4-scenario ApplicationContextRunner matrix (BUDG-05) [wave 2, depends on 06-01]

### Phase 7: Hierarchical Agents

**Goal**: Parent agents spawn child agents via the spawn model (child runs as a tool call) with structured concurrency, bounded depth, and traceable run trees — the largest kore-core change, sequenced last so AgentLoop.kt edits land on top of Phase 5's OBSV-03 changes
**Depends on**: Phase 5 (both modify AgentLoop.kt — hierarchy rebases on the OBSV-03 span work)
**Requirements**: HIER-01, HIER-02, HIER-03, HIER-04
**Success Criteria** (what must be TRUE):

  1. Developer can declare a child agent via `child { }` inside the `agent { }` DSL; the child runs as a tool call and its result feeds back into the parent loop as a `ToolResult`
  2. Cancelling a parent agent cancels all running child agents — verified by a cancellation-propagation test (child cancelled promptly after parent cancellation)
  3. Child spawning beyond the configurable `maxDepth` (default 5) yields a `ToolError` — unbounded recursion is impossible
  4. Audit log records `parent_run_id` on child agent runs, so a developer can trace a full run tree from the database
  5. Existing single-agent definitions compile and run unchanged — all new `AgentLoop`/`AgentTask` parameters have defaults (binary compatibility preserved)

**Plans**: 5 plans (4 base + 1 gap closure)
Plans:

**Wave 1**

- [x] 07-01-PLAN.md — kore-core foundation: AgentTask depth/parentRunId + AgentLoop maxDepth + D-05 Cancelled audit + InMemoryAuditLog run list + ChildDispatchBinder bind seam (HIER-02/03/04, criterion #5) [wave 1]

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 07-02-PLAN.md — kore-storage V2 migration (parent_run_id, no FK) + AgentRunsTable column + adapter INSERT + Testcontainers persistence tests (HIER-04, criterion #4) [wave 2, depends 07-01]
- [x] 07-03-PLAN.md — AgentTool (ToolProvider+ChildDispatchBinder) + AgentBuilder child{}/maxDepth/buildLoop/tracer + AgentToolTest + AgentLoopCancellationTest (HIER-01/02/03, criteria #1/#2/#3) [wave 2, depends 07-01]

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 07-04-PLAN.md — kore-spring KoreProperties.hierarchy.maxDepth + KoreAgentFactory threading + ApplicationContextRunner test (HIER-03) [wave 3, depends 07-03]

**Gap closure** *(verify scored 3/5; CR-01/CR-02 production fixes + concurrency/no-throw tests already committed in eb370c0; this plan closes the one remaining persistent-path test gap)*

- [x] 07-05-PLAN.md — non-UUID agentId persistence + non-UUID parent/child correlation integration test (Gap 2 missing #3) + re-assert committed CR-01 concurrency / CR-02 no-throw unit tests green (HIER-02/03/04, criteria #2/#4) [gap closure, no deps]

## Progress

**Execution Order:**
Phases execute in numeric order: 5 → 6 → 7 (Phase 6 has no dependency on Phase 5 and may run in parallel; Phase 7 must follow Phase 5)

| Phase | Milestone | Plans Complete | Status   | Completed  |
| ----- | --------- | -------------- | -------- | ---------- |
| 1. Core Runtime               | v0.0.1 | 7/7 | Complete | 2026-04-11 |
| 2. Observability & Storage    | v0.0.1 | 3/3 | Complete | 2026-04-13 |
| 3. Skills, Spring & Dashboard | v0.0.1 | 5/5 | Complete | 2026-04-14 |
| 4. Event Bus & Publishing     | v0.0.1 | 6/6 | Complete | 2026-04-15 |
| 5. CI Baseline & Skill Observability | v0.0.2 | 4/4 | Complete    | 2026-06-20 |
| 6. Real Budget Enforcement           | v0.0.2 | 2/2 | Complete    | 2026-06-21 |
| 7. Hierarchical Agents               | v0.0.2 | 5/5 | Complete    | 2026-06-22 |
