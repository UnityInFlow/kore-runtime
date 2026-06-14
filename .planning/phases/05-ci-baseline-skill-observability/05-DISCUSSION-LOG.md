# Phase 5: CI Baseline & Skill Observability - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-14
**Phase:** 5-CI Baseline & Skill Observability
**Areas discussed:** SkillRegistry port shape, SkillActivated event design, integrationTest task layout, CI job shape

---

## SkillRegistry port shape

### How should skill names become available to the agent loop?

| Option | Description | Selected |
|--------|-------------|----------|
| Change return type | `activateFor()` returns `List<ActivatedSkill>` (name + prompt). Breaking, but pre-1.0, 2 in-repo impls. Cleanest. | ✓ |
| Additive method | Keep `activateFor()`; add richer method with default impl. Compat-preserving, two overlapping methods forever. | |
| Count-only, no names | Keep `List<String>`. Span gets count+duration but no names. Arguably fails OBSV-03. | |

**User's choice:** Change return type.

### What fields should ActivatedSkill carry?

| Option | Description | Selected |
|--------|-------------|----------|
| name + prompt | Minimal — exactly what the loop needs. Easy to extend later. | ✓ |
| name + prompt + metadata | Also description/version/trigger. Speculative — nothing in Phase 5 consumes it. | |

**User's choice:** name + prompt.

### How should skill-name attributes appear on the span?

| Option | Description | Selected |
|--------|-------------|----------|
| Array attribute | `kore.skill.names` as OTel string-array + count + duration_ms. Native type, backend-filterable. Needs `withSpan` `List<String>` branch. | ✓ |
| Comma-joined string | One joined string. Zero `withSpan` change, but not individually indexable. | |

**User's choice:** Array attribute.

### Emit the span when zero skills match?

| Option | Description | Selected |
|--------|-------------|----------|
| Always emit | count=0 span on every run. Makes no-match debuggable. Matches current behavior. | ✓ |
| Only when ≥1 matches | Less noise, but silent no-match invisible in traces. | |

**User's choice:** Always emit.

---

## SkillActivated event design

### One batch event per run, or one per skill?

| Option | Description | Selected |
|--------|-------------|----------|
| One batch event | Single event per run with full names list. Mirrors span, fewer messages. | ✓ |
| One event per skill | N events for N skills. Simpler per-skill tagging, multiplies traffic. | |

**User's choice:** One batch event.

### Payload?

| Option | Description | Selected |
|--------|-------------|----------|
| agentId + names + durationMs | Count derivable; everything metrics observer needs; serializes cleanly. | ✓ |
| agentId + names only | No latency metric. | |
| Full prompts included | Heavy + potentially sensitive on the bus. | |

**User's choice:** agentId + names + durationMs.

### Emit event when zero skills matched?

| Option | Description | Selected |
|--------|-------------|----------|
| Only when ≥1 skill | Matches OBSV-04 wording, lean broker traffic. No-match covered by span. | ✓ |
| Always emit (symmetric with span) | Enables bus-only no-match-rate metric, extra event per run. | |

**User's choice:** Only when ≥1 skill.

### How should the observers react?

| Option | Description | Selected |
|--------|-------------|----------|
| Metrics yes, spans no-op | Metrics observer counts + records duration; span observer explicit no-op (avoids duplicate span). | ✓ |
| Both observers handle it | Span observer also synthesizes a span. Speculative; risks duplicate spans in-process. | |

**User's choice:** Metrics yes, spans no-op.

---

## integrationTest task layout

### Task structure?

| Option | Description | Selected |
|--------|-------------|----------|
| Tag-filtered task | New `Test` task on existing `src/test`, `includeTags("integration")`. Zero moves, matches CI-01. | ✓ |
| Dedicated source set | `src/integrationTest` via JVM Test Suites. More plumbing, no functional gain here. | |

**User's choice:** Tag-filtered task.

### Zero-test guard?

| Option | Description | Selected |
|--------|-------------|----------|
| afterSuite assertion | Listener tallies tests, throws GradleException if 0. Self-contained, local + CI. | ✓ |
| failOnNoMatchingTests only | Only fires on empty `--tests` filter; a 0-tag-match can still pass. | |
| CI-side grep on XML | Guard only in CI; local can silently pass with 0 tests. | |

**User's choice:** afterSuite assertion.

### Wire into build/check?

| Option | Description | Selected |
|--------|-------------|----------|
| Decoupled — CI-only | Runs on explicit invocation or CI job. Keeps build fast/Docker-free. Mirrors existing excludeTags. | ✓ |
| Wire into check | `check dependsOn integrationTest`. Stronger local net, forces Docker on every contributor. | |

**User's choice:** Decoupled — CI-only.

---

## CI job shape

### Where do integration tests run?

| Option | Description | Selected |
|--------|-------------|----------|
| Separate job, needs build | New `integration-test` job after `build`. Clean separation, unambiguous Docker failures. | ✓ |
| Step in existing build job | Couples Docker to main build; arm64-build would gate on integration. | |

**User's choice:** Separate job, needs build.

### Trigger events?

| Option | Description | Selected |
|--------|-------------|----------|
| PR + push to main | Same as build job. Catches regressions before merge. | ✓ |
| Push to main only | Lighter PR load, but regression not caught until on main. | |

**User's choice:** PR + push to main.

### Docker pre-flight failure behavior?

| Option | Description | Selected |
|--------|-------------|----------|
| Fail the job loudly | `docker info` non-zero fails with clear message. Distinguishes infra from test failure. | ✓ |
| Skip integration tests | Goes green on no-Docker — the silent-pass mode CI-02 exists to prevent. | |

**User's choice:** Fail the job loudly.

### Is the Gradle guard enough for CI-02?

| Option | Description | Selected |
|--------|-------------|----------|
| Gradle guard is enough | afterSuite GradleException fails the job; one assertion, local + CI identical. | ✓ |
| Add CI-level XML assertion too | Belt-and-suspenders, two guards to keep in sync. | |

**User's choice:** Gradle guard is enough.

---

## Claude's Discretion

- Exact Gradle DSL for task registration and the `afterSuite` counter (config-cache-safe form).
- Package location of `ActivatedSkill` within kore-core (alongside `SkillRegistry.kt` in `core/port/`).
- How the raw-`Tracer` span code in `AgentLoop.runLoop()` sets the array/count/duration attributes (AgentLoop uses nullable raw `Tracer`, not `KoreTracer.withSpan`).
- Duration measurement approach (`System.nanoTime()` around `activateFor`).
- kore-skills `SkillRegistryAdapter` migration to return `ActivatedSkill(name, prompt)`.

## Deferred Ideas

- Richer `ActivatedSkill` metadata (description, version, trigger) — future, via default-valued fields.
- `EventBusSpanObserver` synthesizing skill spans from events — remote-bus topologies only, out of v0.0.2 single-JVM scope.
- Always-emit `SkillActivated` (count=0) for a bus-only no-match-rate metric — deferred for broker frugality.
- Wiring `integrationTest` into `check` — deferred to keep default build Docker-free.
