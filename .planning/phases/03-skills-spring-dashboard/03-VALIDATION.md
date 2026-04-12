---
phase: 3
slug: skills-spring-dashboard
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-12
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Kotest matchers |
| **Config file** | `build.gradle.kts` (each module) |
| **Quick run command** | `./gradlew :kore-skills:test :kore-spring:test :kore-dashboard:test` |
| **Full suite command** | `./gradlew test jacocoTestCoverageVerification` |
| **Estimated runtime** | ~60 seconds (includes Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run module-scoped tests
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | Status |
|---------|------|------|-------------|-----------|-------------------|--------|
| 3-01-01 | 03-01 | 1 | SKIL-01..04 (contracts), D-11 (OTel span) | unit | `./gradlew :kore-core:test --tests "*SkillRegistry*"` | ⬜ pending |
| 3-01-02 | 03-01 | 1 | SKIL-01..04 (YAML loader), D-09/10 | unit + testcontainers | `./gradlew :kore-skills:test :kore-storage:test` | ⬜ pending |
| 3-02-01 | 03-02 | 2 | SPRN-01..04 | unit | `./gradlew :kore-spring:test` | ⬜ pending |
| 3-02-02 | 03-02 | 2 | SPRN-02/03, D-15 (LLM backend beans) | unit | `./gradlew :kore-spring:test` | ⬜ pending |
| 3-03-01 | 03-03 | 2 | DASH-01..03 (fragments) | unit | `./gradlew :kore-dashboard:test` | ⬜ pending |
| 3-03-02 | 03-03 | 2 | DASH-04 (Ktor server) | unit | `./gradlew :kore-dashboard:test` | ⬜ pending |
| 3-04-01 | 03-04 | 3 | all | integration | `./gradlew :kore-spring:test --tests "*IntegrationTest"` | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `kore-skills` module scaffold with jackson-dataformat-yaml dependency
- [ ] `kore-spring` module scaffold with Spring Boot 4 starter parent
- [ ] `kore-dashboard` module scaffold with Ktor 3.2 + ktor-server-htmx

*Wave 0 creates module infrastructure before implementation tasks in Plan 03-01.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| HTMX dashboard renders correctly in browser | DASH-01..04 | Visual rendering | Start test Spring Boot app, open http://localhost:8080/kore, verify 3 fragments poll and update |
| Skill YAML drop-in works without restart | SKIL-03 | Filesystem interaction | Drop new skill file in configured directory, verify it loads on next agent invocation |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
