# Phase 4 — Deferred Items

Items discovered during Phase 4 execution that are out-of-scope for the
current plan. Tracked here per the GSD scope-boundary rule: do not auto-fix
unrelated failures.

---

## 04-06 Phase A — `kore-observability:test` pre-existing failure

**Discovered:** 2026-04-15 during plan 04-06 Phase A regression build
(`./gradlew clean build --no-configuration-cache` after the `0.0.1-SNAPSHOT
→ 0.0.1` version bump).

**Symptom:**

```
ObservableAgentRunnerTest > run() produces a finished kore_agent_run span() FAILED
    java.lang.NoClassDefFoundError at ObservableAgentRunnerTest.kt:40
        Caused by: java.lang.ExceptionInInitializerError at ObservableAgentRunnerTest.kt:76

23 tests completed, 12 failed
> Task :kore-observability:test FAILED
```

All 12 failures in the `kore-observability` module are
`NoClassDefFoundError` / `ExceptionInInitializerError` originating at
`ObservableAgentRunnerTest.kt:76` (and one at line 88 for the
`typeName()` test). The error is class-initialization-level, not a
test-logic failure — suggests a missing transitive test-runtime
dependency or an OTel SDK class-loader issue.

**Verification this is pre-existing, not caused by Phase A:**

Ran `git stash` of the Phase A version bump + workflow files, executed
`./gradlew :kore-observability:test --no-configuration-cache` against an
otherwise clean main tree, and observed the identical 12 failures with
the identical stack trace. The failures exist on `main` prior to any
Phase A edits and are unrelated to the `0.0.1-SNAPSHOT → 0.0.1` version
bump.

**Scope decision:**

NOT fixed in plan 04-06 Phase A. Per the executor scope boundary, only
issues directly caused by the current task's changes are auto-fixed.
This failure predates plan 04-06 and belongs to the `kore-observability`
subsystem owned by Phase 2 (plan 02-01).

**Impact on release:**

- The `release-dry-run.yml` and `release.yml` workflows both run
  `./gradlew clean build` which includes `kore-observability:test`. If
  this failure is still present when the user triggers the dry-run, the
  dry-run will fail and the real release tag must not be pushed.
- **Action required before release:** triage `ObservableAgentRunnerTest`
  — likely a missing opentelemetry-sdk-testing runtime dependency or a
  Java 21 / Kotlin 2.3 OTel SDK incompatibility. A separate gap-closure
  plan should be scheduled before executing Phase B of 04-06.

**Follow-up plan:** To be created as `04-07` (or a dedicated
`gsd-debug` session) to triage and fix the `ObservableAgentRunnerTest`
class-loader failure. Do not proceed past the Phase A + human pre-flight
gate until resolved.
