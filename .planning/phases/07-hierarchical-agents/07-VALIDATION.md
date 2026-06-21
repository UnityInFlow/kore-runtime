---
phase: 7
slug: hierarchical-agents
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-21
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Kotest assertions + MockK + kotlinx-coroutines-test; Testcontainers for Postgres integration |
| **Config file** | `build.gradle.kts` (per-module test + integrationTest tasks) |
| **Quick run command** | `./gradlew :kore-core:test` |
| **Full suite command** | `./gradlew test integrationTest` |
| **Estimated runtime** | ~{N} seconds (planner to fill) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :kore-core:test`
- **After every plan wave:** Run `./gradlew test integrationTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** {N} seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| {N}-01-01 | 01 | 1 | HIER-{XX} | T-7-01 / — | {expected secure behavior or "N/A"} | unit | `{command}` | ✅ / ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Cancellation-propagation test stub (HIER-02, criterion #2)
- [ ] Depth-limit ToolError test stub (HIER-03, criterion #3)
- [ ] D-01/D-03 result-mapping asymmetry test stubs (HIER-01)
- [ ] Testcontainers `parent_run_id` persistence assertion (HIER-04, criterion #4)

*If none: "Existing infrastructure covers all phase requirements."*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| {behavior} | HIER-{XX} | {reason} | {steps} |

*If none: "All phase behaviors have automated verification."*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < {N}s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
