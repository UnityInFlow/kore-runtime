---
slug: kore-observability-test-noclassdeffound
status: resolved
trigger: "kore-observability:test fails with 12 NoClassDefFoundError / ExceptionInInitializerError in ObservableAgentRunnerTest on current main"
created: 2026-04-15
updated: 2026-04-16
---

# Debug Session: kore-observability:test NoClassDefFoundError

## Symptoms

**Expected behavior:**
`./gradlew :kore-observability:test` exits 0 with all tests green. This module has been green through Phase 2 (observability-storage) and Phase 3 (skills-spring-dashboard), and the release-dry-run.yml workflow added in plan 04-06 Phase A depends on a green `./gradlew build` across the whole project.

**Actual behavior:**
`./gradlew clean build` fails during `:kore-observability:test` execution with 12 test failures across two test classes: `ObservableAgentRunnerTest` (8 failures) and `EventBusMetricsObserverTest` (4 failures). Failures surface as `NoClassDefFoundError: kotlinx/serialization/KSerializer` caused by `ClassNotFoundException` or `ExceptionInInitializerError`.

**Error messages:**
```
java.lang.NoClassDefFoundError: kotlinx/serialization/KSerializer
  at dev.unityinflow.kore.core.AgentResult.<clinit>(AgentResult.kt:26)
  Caused by: java.lang.ClassNotFoundException: kotlinx.serialization.KSerializer

java.lang.NoClassDefFoundError: kotlinx/serialization/KSerializer
  at dev.unityinflow.kore.core.AgentEvent.<clinit>(AgentEvent.kt:19)
  Caused by: java.lang.ClassNotFoundException: kotlinx.serialization.KSerializer
```

**Timeline:**
- Phase 2 built kore-observability (OTel + Micrometer observers). All tests were green at Phase 2 verification.
- Phase 3 and Phase 4 Waves 1-5 (plans 04-01 through 04-05) did not touch kore-observability source files.
- The failure was discovered during plan 04-06 Phase A's post-implementation regression check.
- Root cause: kore-core added `@Serializable` annotations to `AgentResult` and `AgentEvent` sealed classes during Phase 2/3, with `kotlinx-serialization-core` declared as `compileOnly`. This design works at compile time but breaks any downstream module that instantiates or references these classes at test runtime without adding serialization-core to its own classpath.

**Reproduction:**
```bash
./gradlew :kore-observability:test --rerun-tasks
```

## Current Focus

```yaml
hypothesis: CONFIRMED - kotlinx-serialization-core missing from kore-observability test classpath
test: Added testImplementation(libs.serialization.core) to kore-observability/build.gradle.kts
expecting: All 23 tests pass
next_action: none - fix verified, session resolved
```

## Evidence

- timestamp: 2026-04-16T05:00
  checked: Full stack trace from ObservableAgentRunnerTest XML test results
  found: |
    Root exception is `java.lang.ClassNotFoundException: kotlinx.serialization.KSerializer`.
    Triggered at `AgentResult.<clinit>(AgentResult.kt:26)` when MockK's Objenesis
    instantiator invokes `AgentLoop.run()` which returns `AgentResult`.
    The Kotlin serialization compiler plugin generates a companion object with
    `serializer()` that references `KSerializer` - this runs at class init time.
  implication: The missing class is kotlinx.serialization.KSerializer, not an OTel class. Initial OTel version skew hypothesis was wrong.

- timestamp: 2026-04-16T05:00
  checked: Full stack trace from EventBusMetricsObserverTest XML test results
  found: |
    Same root exception: `ClassNotFoundException: kotlinx.serialization.KSerializer`
    at `AgentEvent.<clinit>(AgentEvent.kt:19)`. Same mechanism - @Serializable
    generates clinit code referencing KSerializer.
  implication: Both failing test classes hit the same root cause - missing serialization runtime.

- timestamp: 2026-04-16T05:01
  checked: kore-core/build.gradle.kts dependency declarations
  found: |
    kore-core declares:
      compileOnly(libs.serialization.core)
      compileOnly(libs.serialization.json)
      testImplementation(libs.serialization.json)
    The compileOnly scope means these deps do NOT propagate to downstream
    modules via implementation(project(":kore-core")).
  implication: |
    Any module depending on kore-core that instantiates AgentResult or
    AgentEvent (directly or via MockK reflection) needs serialization-core
    on its test classpath.

- timestamp: 2026-04-16T05:01
  checked: kore-observability/build.gradle.kts dependency declarations
  found: No kotlinx-serialization dependency of any kind. Only OTel and Micrometer deps.
  implication: Confirmed - kore-observability test classpath is missing kotlinx-serialization-core.

- timestamp: 2026-04-16T05:02
  checked: Other kore modules that depend on kore-core without serialization
  found: |
    kore-dashboard, kore-skills, kore-spring, kore-storage also lack serialization
    deps. However, only kore-observability was reported as failing - the others
    may not instantiate AgentResult/AgentEvent in their tests, or may get it
    transitively from other deps.
  implication: Potential latent issue in other modules but out of scope for this session.

- timestamp: 2026-04-16T05:03
  checked: ./gradlew :kore-observability:test --rerun-tasks after fix
  found: "BUILD SUCCESSFUL in 7s" - 23 tests completed, 0 failures.
  implication: Fix verified at module level.

- timestamp: 2026-04-16T05:05
  checked: ./gradlew clean build --no-configuration-cache (excluding kore-llm, kore-spring, kore-storage:test)
  found: "BUILD SUCCESSFUL in 19s" - 102 actionable tasks executed.
  implication: |
    Fix verified at project level. Excluded modules are known separate issues:
    kore-llm (langchain4j version skew), kore-spring (depends on kore-llm),
    kore-storage:test (needs Docker for Testcontainers).

## Eliminated Hypotheses

- hypothesis: OTel SDK version skew between kore-observability compile scope and test runtime scope
  evidence: |
    The actual ClassNotFoundException names `kotlinx.serialization.KSerializer`, not any
    OTel class. The OTel version mismatch (1.61.0 extension-kotlin vs 1.49.0 API) exists
    but is not the cause of this failure.
  timestamp: 2026-04-16T05:00

- hypothesis: Micrometer/OTel bridge static init failure
  evidence: The <clinit> failure is in AgentResult and AgentEvent (kore-core classes), not in any Micrometer or OTel bridge class.
  timestamp: 2026-04-16T05:00

- hypothesis: Spring Boot 4 BOM constraint drift changing OTel resolution
  evidence: The missing class is kotlinx.serialization.KSerializer - completely unrelated to Spring Boot or OTel version management.
  timestamp: 2026-04-16T05:00

## Resolution

root_cause: |
  kore-core's `AgentResult` and `AgentEvent` sealed classes are annotated with
  `@Serializable` from kotlinx.serialization. The Kotlin serialization compiler plugin
  generates a companion object with a `serializer()` method that references
  `kotlinx.serialization.KSerializer` in the class's static initializer (`<clinit>`).
  kore-core declares `kotlinx-serialization-core` as `compileOnly` (so it compiles but
  doesn't propagate to runtime classpath of dependents). kore-observability depends on
  kore-core via `implementation(project(":kore-core"))`, which excludes compileOnly deps.
  When tests in kore-observability instantiate AgentResult (via MockK) or AgentEvent
  (directly), the JVM tries to initialize the class, calls `<clinit>`, and fails with
  `ClassNotFoundException: kotlinx.serialization.KSerializer` because the class is not
  on the test runtime classpath.

fix: |
  Added `testImplementation(libs.serialization.core)` to
  `kore-observability/build.gradle.kts` so that `kotlinx-serialization-core` is available
  on the test classpath. This provides the `KSerializer` class needed by the
  compiler-generated `<clinit>` code in `AgentResult` and `AgentEvent`.

verification: |
  1. `./gradlew :kore-observability:test --rerun-tasks` -> BUILD SUCCESSFUL, 23 tests, 0 failures
  2. `./gradlew clean build --no-configuration-cache` (excluding kore-llm/kore-spring/kore-storage:test) -> BUILD SUCCESSFUL, 102 tasks

files_changed:
  - kore-observability/build.gradle.kts

## Related Files

- `kore-observability/build.gradle.kts` -- dependency declarations (CHANGED)
- `kore-core/build.gradle.kts` -- compileOnly serialization declaration (root cause origin)
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentResult.kt` -- @Serializable sealed class
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt` -- @Serializable sealed class
- `gradle/libs.versions.toml` -- serialization version catalog entries

## Non-goals (out of scope)

- Do NOT fix kore-llm langchain4j-core version skew -- separate known issue, tracked separately
- Do NOT touch Phase 4 work (plans 04-01 through 04-06) unless the fix genuinely lives there
- Do NOT attempt to fix by upgrading Kotlin or Gradle major versions
- Do NOT add a new @Tag("integration") to skip the failing tests
- Do NOT bypass by setting `-x :kore-observability:test` in the release workflows
