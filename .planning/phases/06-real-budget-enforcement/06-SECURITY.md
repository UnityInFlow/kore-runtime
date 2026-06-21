---
phase: 06
slug: real-budget-enforcement
status: secured
threats_open: 0
threats_total: 6
asvs_level: 1
created: 2026-06-21
---

# SECURITY — Phase 06: real-budget-enforcement

**Phase:** 06 — real-budget-enforcement
**ASVS Level:** 1
**Block on:** high
**Audited:** 2026-06-21
**Verdict:** SECURED — 6/6 threats resolved (5 mitigated + verified in code, 1 accepted + logged)

The threat register was authored at plan time (`register_authored_at_plan_time: true`).
This audit VERIFIES each declared mitigation against the implemented code — it does
not scan for new threats. Implementation files were treated as read-only.

---

## Threat Verification

| Threat ID | Category | Disposition | Status | Evidence |
|-----------|----------|-------------|--------|----------|
| T-06-01 | Denial of Service | accept | CLOSED | Accepted-risk entry below; code matches rationale — `BudgetBreakerAdapter.kt:32` `ConcurrentHashMap` with no eviction hook; KDoc `BudgetBreakerAdapter.kt:21-23` documents the no-eviction (D-05) tradeoff bounded by running-agent count, mirroring `InMemoryBudgetEnforcer` (T-03-04). |
| T-06-02 | Denial of Service | mitigate | CLOSED | Hard token-count stop: `BudgetBreakerAdapter.kt:61` `checkBudget = !(trackers[agentId]?.isAboveHardLimit() ?: false)`. Asserted: `BudgetBreakerAdapterTest.kt:32` (`checkBudget("a") shouldBe false` after overshoot). |
| T-06-03 | Tampering | mitigate | CLOSED | No-escape boundary: `BudgetBreakerAdapter.kt:52-58` wraps `tracker.add(...)` in `try { } catch (_: BudgetException)` (sealed base — covers `BudgetHardLimitException`). Asserted: `BudgetBreakerAdapterTest.kt:28` `shouldNotThrowAny { recordUsage(...) }` past the limit, and `:76-88` concurrency stress `shouldNotThrowAny`. |
| T-06-04 | Denial of Service | mitigate | CLOSED | `@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"])` STRING form: `KoreAutoConfiguration.kt:180`. Lazy resolution proven by FilteredClassLoader scenario: `BudgetBreakerAutoConfigurationTest.kt:59-70` (context still starts, default bean retained, with `enabled=true`). |
| T-06-05 | Tampering | mitigate | CLOSED | Both candidates carry `@ConditionalOnMissingBean(BudgetEnforcer::class)`: default `KoreAutoConfiguration.kt:63`, adapter `KoreAutoConfiguration.kt:189`. Only one gate satisfiable per app. 4-scenario `ApplicationContextRunner` matrix `BudgetBreakerAutoConfigurationTest.kt:34-70` asserts exact concrete type per scenario; `getBean(BudgetEnforcer::class.java)` throws on duplicates (single-bean guard). |
| T-06-SC | Tampering (supply chain) | mitigate | CLOSED (with note) | Dependency coordinate `implementation("io.github.unityinflow:budget-breaker:0.0.1")` present at `kore-budget/build.gradle.kts:11` — matches the audited coordinate. Legitimacy: first-party UnityInFlow org artifact, Sonatype-verified `io.github.unityinflow` namespace; verdict **OK/Approved** in `06-RESEARCH.md:104-113` (Package Legitimacy Audit) via live Maven Central `maven-metadata.xml` + downloaded jar `javap` bytecode inspection. See supply-chain note below re: version staleness. |

**Threats Closed:** 6/6.

---

## Accepted Risks Log

### T-06-01 — Unbounded `trackers` map (no eviction)

- **Component:** `BudgetBreakerAdapter.trackers` (`ConcurrentHashMap<String, TokenTracker>`), `BudgetBreakerAdapter.kt:32`.
- **Disposition:** accept (D-05).
- **Rationale (verified against code):** One `TokenTracker` is created per `agentId`
  (= `AgentTask.id`, a per-run UUID) and never evicted. State lives for the process
  lifetime, bounded by the count of distinct agent ids seen. There is no run-end hook
  on the `BudgetEnforcer` port and D-00 forbids adding one. This exactly mirrors the
  already-accepted `InMemoryBudgetEnforcer` tradeoff (T-03-04). The code contains no
  eviction logic and the public KDoc (`BudgetBreakerAdapter.kt:21-23`) documents the
  tradeoff — accept rationale and implementation are consistent.
- **Residual risk:** Memory growth proportional to distinct-agent-id count over a
  process lifetime. Acceptable for the v0.x in-process model. Revisit if/when a
  lifecycle/run-end hook is added to the port.

---

## Supply-Chain Note (non-blocking, T-06-SC)

`kore-budget/build.gradle.kts:11` pins `io.github.unityinflow:budget-breaker:0.0.1`
as a raw string literal (not a `gradle/libs.versions.toml` catalog alias). The prior
code review flagged this as **WR-02**.

- **Legitimacy (the T-06-SC threat itself):** CLOSED. The coordinate is a first-party
  artifact on the Sonatype-verified namespace, byte-verified this session
  (`06-RESEARCH.md:104-113`). The threat is the dependency's *legitimacy*, and that is
  established — so T-06-SC is not blocking.
- **Quality/staleness observations (out of scope for threat closure, recorded for the maintainer):**
  1. Version is hardcoded rather than catalogued, contrary to the project's
     version-catalog convention (the lone uncatalogued version in the build).
  2. `06-REVIEW.md` WR-02 states the locally published artifact is `0.1.0` and treats
     `0.0.1` as stale. This conflicts with `06-RESEARCH.md:100,507`, which report that
     `maven-metadata.xml` on `repo1.maven.org` lists **only `0.0.1`** as published
     (and `budget-breaker-spring-boot-starter` returns 404). If `0.0.1` is in fact the
     only Central-published version, the pin is correct for reproducible resolution and
     `0.1.0` is a local-only tag. This discrepancy is a quality/release-hygiene item for
     the maintainer to reconcile; it does not affect the legitimacy verdict and is not a
     security blocker under `block_on: high`.

---

## Unregistered Flags

None. Neither `06-01-SUMMARY.md` nor `06-02-SUMMARY.md` contains a `## Threat Flags`
section, and no new attack surface was introduced beyond the registered threats. Every
new symbol (the `kore-budget` module, `BudgetBreakerAdapter`, the
`BudgetBreakerAutoConfiguration` triple-gate, the `enabled` property, and the new
dependency) maps to an existing threat ID (T-06-01..05, T-06-SC).

---

## Implementation Integrity Checks

- `BudgetEnforcer` port unchanged (D-00) — adapter implements the three existing
  `suspend` signatures (`recordUsage`, `checkBudget`, `getUsage`) without altering them.
- Existing `inMemoryBudgetEnforcer` default bean (`KoreAutoConfiguration.kt:62-65`)
  unchanged — zero behavior change for apps that do not opt in.
- No implementation files were modified by this audit.
