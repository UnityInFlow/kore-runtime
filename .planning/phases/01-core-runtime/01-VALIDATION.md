---
phase: 1
slug: core-runtime
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-10
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Kotest matchers |
| **Config file** | `build.gradle.kts` (each module) |
| **Quick run command** | `./gradlew test` |
| **Full suite command** | `./gradlew test jacocoTestCoverageVerification` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test`
- **After every plan wave:** Run `./gradlew test jacocoTestCoverageVerification`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| *(populated by planner)* | | | | | | | | | |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Multi-module Gradle scaffold with `gradle/libs.versions.toml`
- [ ] JUnit 5 + Kotest matchers configured in each module
- [ ] JaCoCo plugin with 80% minimum line coverage
- [ ] ktlint configured via kotlinter-gradle

*Wave 0 creates the build infrastructure before any implementation tasks.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| MCP stdio transport works with real npx server | MCP-01 | Requires external process | Start MCP server, run agent, verify tool call succeeds |
| MCP SSE transport works with remote server | MCP-02 | Requires network | Connect to SSE endpoint, verify tools/list response |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
