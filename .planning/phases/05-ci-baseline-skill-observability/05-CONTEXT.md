# Phase 5: CI Baseline & Skill Observability - Context

**Gathered:** 2026-06-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase closes the last two correctness gaps from v0.0.1:

1. **CI correctness** — kore-storage's 7 Testcontainers integration tests run via a dedicated `./gradlew :kore-storage:integrationTest` task and execute in CI against real PostgreSQL on `arc-runner-unityinflow`, failing loudly if 0 tests run (CI-01, CI-02).
2. **Skill observability** — a skill activation emits both a `kore.skill.activate` OTel span (correctly parented under the agent-run span, carrying skill name/count/duration) and an `AgentEvent.SkillActivated` event on the event bus for metrics observers (OBSV-03, OBSV-04).

It does NOT add new runtime features — it makes existing skill activation observable and existing integration tests CI-runnable. Phase 7 (Hierarchical Agents) rebases its `AgentLoop.kt` edits on top of the OBSV-03 span work landed here.

</domain>

<decisions>
## Implementation Decisions

### SkillRegistry port shape
- **D-01:** `SkillRegistry.activateFor()` return type changes from `List<String>` to `List<ActivatedSkill>` — a **breaking port change**, acceptable because the project is pre-1.0 and there are only two implementations (`NoOpSkillRegistry` in kore-core, `SkillRegistryAdapter` in kore-skills), both in-repo. No dual-method/compat shim — clean single shape.
- **D-02:** `ActivatedSkill` is a new data class living in kore-core next to the `SkillRegistry` port, with **minimal fields: `name` and `prompt`**. The loop uses `prompt` for system-message injection (existing behavior) and `name` for span/event attributes. Extend later with default-valued fields (description, version, trigger) only when a consumer needs them.
- **D-03:** Span attributes on `kore.skill.activate`: `kore.skill.names` as a native **OTel string-array attribute**, plus `kore.skill.count` (long) and `kore.skill.duration_ms` (long). New constants added to `KoreAttrs` in kore-observability. **`KoreTracer.withSpan`'s `attrs: Map<String, Any>` branching needs a `List<String>` (string-array) case added** — it currently handles only String/Long/Int/Double/Boolean.
- **D-04:** The `kore.skill.activate` span is **always emitted** when a tracer is present, including when zero skills match (count=0). This matches the current unconditional span creation in `AgentLoop.runLoop()` and makes "why didn't my skill activate?" answerable from the trace alone (matching ran, took N ms, 0 matched).

### SkillActivated event design
- **D-05:** **One batch event per agent run** — a single `AgentEvent.SkillActivated` carrying the full list of activated skill names. Mirrors the one-span-per-activation-pass model and keeps bus traffic low; metrics observers can still iterate names for per-skill counts.
- **D-06:** Payload: `agentId: String`, `skillNames: List<String>`, `durationMs: Long`. Count is derivable from `skillNames.size`. **Prompts are NOT included** (can be KBs; may contain sensitive content — unsuitable for broker transport). New subclass follows the existing `@Serializable` + `@SerialName("SkillActivated")` pattern of the other `AgentEvent` subclasses.
- **D-07:** The event is emitted **only when ≥1 skill matched** — matches OBSV-04's "skill activation emits" wording and keeps Kafka/RabbitMQ traffic lean for the common no-skills case. No-match visibility is already covered by the always-emitted span (D-04). (Note the deliberate asymmetry: span always emits, event only on ≥1 match.)
- **D-08:** Observer reactions to the new subclass (both exhaustive `when` blocks must handle it):
  - `EventBusMetricsObserver` — increment a `kore.skills.activated` counter (tagged per skill name) and record `durationMs`.
  - `EventBusSpanObserver` — **explicit no-op branch**. The real span is created in-process by `AgentLoop`; synthesizing a second span from the event would duplicate it in the default in-process topology. (The Started/Completed-pair pattern the span observer uses doesn't fit a single instantaneous event anyway.)
  - `EventBusDashboardObserver` (if its `when` is also exhaustive) — verify it handles or no-ops the new subclass so it still compiles.

### integrationTest task layout
- **D-09:** A **tag-filtered `Test` task** named `integrationTest` on the **existing `src/test` source set** with `includeTags("integration")` — no source-set move (the 3 test files are already `@Tag("integration")`). Matches CI-01's "tag-filtered" wording; tests keep sharing fixtures/deps with unit tests.
- **D-10:** **Zero-test guard via an `afterSuite`/test-listener tally** that throws `GradleException` if the executed-test count is 0. Self-contained in `kore-storage/build.gradle.kts`, runs identically locally and in CI, and catches a silently-empty tag filter (the exact failure mode that lets a broken filter pass green). This single guard also satisfies CI-02's "assert tests actually executed."
- **D-11:** `integrationTest` is **decoupled from the `build`/`check` lifecycle** — it runs only on explicit invocation or in the CI integration job. Keeps `./gradlew build` fast and Docker-free for contributors without Docker, mirroring the existing `tasks.test { excludeTags("integration") }` intent. The existing unit `test` task already excludes the `integration` tag and must continue to.

### CI job shape
- **D-12:** A **separate `integration-test` job** in `.github/workflows/ci.yml` on `[arc-runner-unityinflow]` that declares `needs: build`. Keeps unit build/lint fast and Docker-free; isolates the Docker-dependent run so its failures (or a missing Docker daemon) are unambiguous and don't mask unit failures. (Note: the existing `arm64-build` job also `needs: build` — the new job runs in parallel with it, not gating it.)
- **D-13:** Triggers match the existing `build` job: **PR + push to `main`**. Integration regressions are caught at PR review time, before merge — the point of CI-02. Self-hosted runner capacity makes the per-PR Docker job acceptable.
- **D-14:** **`docker info` is a real pre-flight step that fails the job loudly** (non-zero exit, clear "Docker unavailable" message) before tests run. A missing daemon is a runner config error that must be visible — never silently skipped. Distinguishes "infra broken" from "tests failed."
- **D-15:** CI-02's "assert tests executed" is satisfied entirely by the in-Gradle `afterSuite` guard (D-10) — **no separate CI-side XML-parsing step**. One assertion, runs identically local + CI, nothing to keep in sync.

### Claude's Discretion
- Exact Gradle DSL for the `integrationTest` task registration and the `afterSuite` counter (e.g., `TestListener` vs `afterSuite` closure vs an `AtomicInteger` in a `doLast`) — pick the idiomatic Kotlin-DSL form that survives Gradle config-cache.
- Where exactly `ActivatedSkill` sits in the kore-core package tree (alongside `SkillRegistry.kt` in `core/port/` is the natural home).
- How the raw-`Tracer` span code in `AgentLoop.runLoop()` (currently `tracer?.spanBuilder(...)`) sets the new array/count/duration attributes — AgentLoop uses a nullable raw `Tracer`, not `KoreTracer`, so it builds attributes directly on the `Span`. Keep the `KoreAttrs` constants as the single source of attribute-key truth even though AgentLoop doesn't route through `KoreTracer.withSpan`.
- Whether `durationMs` for the span and event is measured with `System.nanoTime()` around the `activateFor` call (no wall-clock dependency — fine for a duration).
- kore-skills `SkillRegistryAdapter` migration: it must now return `ActivatedSkill(name, prompt)` — the skill name comes from the YAML skill definition it already loads.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & Roadmap
- `.planning/REQUIREMENTS.md` §Observability, §CI/Testing — OBSV-03, OBSV-04, CI-01, CI-02 acceptance wording
- `.planning/ROADMAP.md` §"Phase 5: CI Baseline & Skill Observability" — the 4 Success Criteria (what must be TRUE)

### Skill activation (OBSV-03 / port change)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/SkillRegistry.kt` — the port + `NoOpSkillRegistry` being changed to return `List<ActivatedSkill>`
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt` §runLoop (lines ~92–115) — where the `kore.skill.activate` span is built and prompts injected; the raw nullable `Tracer` lives here
- `kore-skills/.../SkillRegistryAdapter.kt` — the real implementation that must migrate to the new return type (locate via `grep -rl SkillRegistryAdapter kore-skills`)

### Observability attributes & events (OBSV-03 / OBSV-04)
- `kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/KoreTracer.kt` — `KoreSpans.SKILL_ACTIVATE` constant (already defined), `KoreAttrs` (add skill attr keys), `withSpan` (add `List<String>` attr branch)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentEvent.kt` — add `SkillActivated` subclass following the `@Serializable`/`@SerialName` pattern
- `kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusMetricsObserver.kt` — add counter + duration handling for `SkillActivated`
- `kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusSpanObserver.kt` — add explicit no-op branch for `SkillActivated`
- `kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/ObservableAgentRunner.kt` — creates the `kore.agent.run` parent span; confirms span parenting context for OBSV-03

### CI / integration tests (CI-01 / CI-02)
- `kore-storage/build.gradle.kts` — add `integrationTest` task + zero-test guard; existing `tasks.test { excludeTags("integration") }` is the pattern to mirror
- `kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/` — `MigrationTest.kt` (3 tests), `PostgresAuditLogAdapterTest.kt`, `PostgresAuditLogAdapterQueryTest.kt`; all `@Tag("integration")` + `@Testcontainers` (7 tests total)
- `.github/workflows/ci.yml` — add the `integration-test` job (needs: build, docker info pre-flight)

### Cross-cutting constraints
- `08-kore-runtime/CLAUDE.md` §Constraints — no `var`, no `!!` without comment, coroutines only, ktlint before commit, JUnit 5 + Kotest assertions, MockK
- `.planning/STATE.md` §Blockers/Concerns — "Docker availability on arc-runner not pre-verified" (drives D-14's loud pre-flight)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `KoreSpans.SKILL_ACTIVATE = "kore.skill.activate"` already exists in `KoreTracer.kt` — the span name constant is ready; AgentLoop currently hardcodes the string and should reference the constant.
- `KoreAttrs` already groups `kore.*` attribute keys by domain (common/LLM/tool) — add a `// Skill` group following the same shape.
- `EventBusMetricsObserver` already has the injected-`CoroutineScope` + agentNameResolver + per-event counter pattern; `SkillActivated` handling slots into its existing `when`.
- `ObservableAgentRunner.withSpan(KoreSpans.AGENT_RUN)` establishes the parent span; the skill span inheriting `Context.current()` is what makes OBSV-03's parenting work.

### Established Patterns
- All `AgentEvent` subclasses are `@Serializable data class` with explicit `@SerialName` (human-readable wire format); `kotlinx-serialization-core` is `compileOnly` in kore-core. `SkillActivated` must follow this exactly so Kafka/RabbitMQ round-trips work.
- kore-core stays zero-runtime-dep except kotlinx.coroutines + stdlib — `ActivatedSkill` must reference only stdlib (a plain data class, no serialization runtime needed beyond the compileOnly annotations if it ends up in an event — but it does NOT go on the bus; only `skillNames: List<String>` does).
- `AgentLoop.tracer` is a nullable raw `Tracer` (compileOnly OTel in kore-core) — graceful degradation when null. The skill span code must keep working with `tracer == null`.
- Integration tests are tag-gated: unit `test` excludes `integration`; the new task includes it. Testcontainers Postgres + `@Testcontainers` JUnit5 extension already wired in `kore-storage` test deps.

### Integration Points
- `SkillRegistry.activateFor()` return-type change ripples to: `NoOpSkillRegistry` (return `emptyList()` — type-only change), `SkillRegistryAdapter` in kore-skills (wrap name+prompt), and `AgentLoop.runLoop()` (consume `.prompt` for injection, `.name` for span/event).
- The new `SkillActivated` subclass forces every exhaustive `when (event)` over `AgentEvent` to add a branch — at minimum the two observers above, possibly `EventBusDashboardObserver`. Compilation will surface them all.
- CI job connects at `.github/workflows/ci.yml` — new job parallel to `arm64-build`, both `needs: build`.

</code_context>

<specifics>
## Specific Ideas

- The span/event asymmetry is intentional and should be documented in code comments: **span always emits (observability/debuggability), event only on ≥1 match (broker frugality)**.
- "Fail loudly if 0 tests run" is the headline behavior for CI-01 — the `afterSuite` `GradleException` is the mechanism, and it must fire both locally (`./gradlew :kore-storage:integrationTest`) and in CI, not just in CI.
- Docker pre-flight failure must read as a *config error*, not a test failure — message wording matters for on-call triage (per STATE.md concern).

</specifics>

<deferred>
## Deferred Ideas

- **Richer `ActivatedSkill` metadata** (description, version, matched trigger pattern) — deferred; nothing in Phase 5 consumes it. Add via default-valued fields when telemetry needs it.
- **`EventBusSpanObserver` synthesizing skill spans from events** — only useful for remote-bus / cross-process consumers; speculative for v0.0.2's single-JVM scope. Revisit if a remote consumer topology appears.
- **Always-emit the `SkillActivated` event (count=0)** to enable a bus-only "no-match rate" metric — deferred in favor of broker frugality; the span already carries count=0.
- **Wiring `integrationTest` into `check`** for stronger local safety — deferred to keep the default build Docker-free.

None of these are in scope for Phase 5.

</deferred>

---

*Phase: 5-CI Baseline & Skill Observability*
*Context gathered: 2026-06-14*
