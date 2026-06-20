---
phase: 5
slug: ci-baseline-skill-observability
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-20
---

# Phase 5 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

This phase adds NO new external inputs, network endpoints, authentication, or persistence.
The change set is build/CI config plus in-process observability instrumentation. Skill
names originate from in-repo YAML (trusted), not user input. The only material surfaces are
(a) the CI runner's dependency on a Docker daemon and (b) what skill-activation telemetry
crosses to the event bus / OTel backend.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| CI runner → Docker daemon | `integration-test` job depends on a Docker daemon on `arc-runner-unityinflow` (unverified per STATE.md). Availability/config boundary, not untrusted-input. | none (infra dependency) |
| AgentLoop → event bus (Kafka/RabbitMQ opt-in) | `AgentEvent.SkillActivated` may reach a remote broker. Payload is the security-relevant surface. | `agentId`, `skillNames`, `durationMs` (no prompts) |
| AgentLoop / KoreTracer → OTel backend | Span attributes (`kore.skill.names`) exported to any configured backend. | skill names (non-PII identifiers) |
| Event bus → metrics backend | `skillName` becomes a metric tag value on `kore.skills.activated`. | skill names (non-PII, low-cardinality) |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-05-01 | Denial of Service (false signal) | CI integration-test job vs missing Docker daemon | mitigate | `docker info` pre-flight fails the job loudly with a "RUNNER CONFIG ERROR, not a test failure" annotation BEFORE tests run (D-14). Verified: `.github/workflows/ci.yml:71-72`. | closed |
| T-05-02 | Tampering (silent green) | integrationTest tag filter matching 0 tests | mitigate | `AtomicInteger` + `addTestListener(TestListener)` zero-test guard throws `GradleException` on count==0 (D-10). Verified: `kore-storage/build.gradle.kts:66,67,89`. | closed |
| T-05-03 | Information Disclosure | `AgentEvent.SkillActivated` payload on the message broker | mitigate | Prompts DELIBERATELY excluded (D-06); payload is `agentId`, `skillNames`, `durationMs` only. Field set verified in `AgentEvent.kt`; serialization round-trip test (`AgentEventSerializationTest`, 7/0/0) guards against accidental field addition. | closed |
| T-05-04 | Information Disclosure | `kore.skill.names` span attribute exported to OTel backend | accept | Skill names are non-PII identifiers from in-repo YAML (Phase-2 D-24). Low risk; documented. | closed |
| T-05-05 | Information Disclosure | string-array span attributes exported to OTel backend | accept | New branch only changes encoding (native array vs comma-string); adds no new data source. Caller-supplied non-PII identifiers. Low risk; documented. | closed |
| T-05-06 | Denial of Service (metrics backend) | high-cardinality `skill_name` tag on `kore.skills.activated` | mitigate | Counter follows the Phase-2 D-24 low-cardinality rule; KDoc explicitly cites T-05-06 and the unbounded-skills follow-up. Verified: `KoreMetrics.kt:107-110`. | closed |
| T-05-07 | Information Disclosure | skill names as metric tag values | accept | Non-PII identifiers; prompts excluded upstream (D-06). Low risk; documented. | closed |
| T-05-SC | Tampering | npm/pip/cargo installs | n/a | No packages installed in any plan (no dependency changes — Testcontainers, OTel, Micrometer, serialization already on the classpath). No package-legitimacy checkpoint required. | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party) · n/a*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-05-01 | T-05-04 | Skill names are non-PII identifiers from in-repo YAML; mirrors Phase-2 D-24 ("configured names, no PII in tag values"). | Jiří Hermann (plan-time disposition) | 2026-06-20 |
| AR-05-02 | T-05-05 | String-array encoding change adds no new data source; values are caller-supplied non-PII identifiers. | Jiří Hermann (plan-time disposition) | 2026-06-20 |
| AR-05-03 | T-05-07 | Skill names as metric tags are non-PII; prompts excluded upstream by D-06. | Jiří Hermann (plan-time disposition) | 2026-06-20 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-06-20 | 8 | 8 | 0 | gsd-secure-phase (orchestrator, register authored at plan time) |

All 8 threats verified CLOSED: 4 `mitigate` dispositions confirmed present in code (T-05-01 ci.yml, T-05-02 build.gradle.kts, T-05-03 AgentEvent.kt, T-05-06 KoreMetrics.kt); 3 `accept` dispositions documented in the Accepted Risks Log; 1 `n/a` (no package installs). Register was authored at plan time across all four PLAN.md `<threat_model>` blocks, so verification was mitigation-existence (not retroactive STRIDE).

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer / n/a)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-06-20
