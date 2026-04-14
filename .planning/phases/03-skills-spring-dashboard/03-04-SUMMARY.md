---
phase: 03-skills-spring-dashboard
plan: 04
subsystem: spring-integration
tags: [kotlin, spring-boot-4, kore-spring, kore-dashboard, integration-test, hexagonal, smart-lifecycle, htmx, gate]

requires:
  - phase: 03-skills-spring-dashboard plan 02
    provides: KoreAutoConfiguration with reflective DashboardServer bridge + KoreProperties.DashboardProperties
  - phase: 03-skills-spring-dashboard plan 03
    provides: kore-dashboard module + DashboardServer (EventBus, AuditLog, DashboardProperties) + DashboardServer.DashboardProperties interface + InertAuditLog sentinel
  - phase: 03-skills-spring-dashboard plan 01
    provides: kore-skills module + SkillRegistryAdapter
  - phase: 01-core-runtime plan 04
    provides: kore-test MockLLMBackend used as the integration test LLM
provides:
  - Direct DashboardServer bean wiring in KoreAutoConfiguration (reflection bridge removed)
  - KoreDashboardPropertiesAdapter (private nested class in KoreAutoConfiguration) bridging KoreProperties.DashboardProperties → DashboardServer.DashboardProperties
  - KoreIntegrationTest — full @SpringBootTest context with one @Bean returning agent { } via the kore DSL, asserting end-to-end module wiring (5 tests)
  - KoreIntegrationTestApp scan root demonstrating the README hero-demo developer experience inside a JUnit context
  - README "Spring Boot — One Dependency, One Bean, Live Dashboard" section (Phase 3 hero demo)
  - .planning/phases/03-skills-spring-dashboard/deferred-items.md tracking pre-existing kore-llm and kore-core lint failures
affects:
  - kore-spring/build.gradle.kts (compileOnly kore-dashboard added; testImplementation kore-dashboard, kore-skills, kore-test added)
  - KoreAutoConfigurationTest (kore.dashboard.enabled=false on both ApplicationContextRunner and the @SpringBootTest, SkillRegistry test inverted to expect SkillRegistryAdapter now that kore-skills is on the test classpath)

tech-stack:
  added: []
  patterns:
    - "Direct constructor wiring instead of Class.forName + getConstructor reflection — kore-dashboard is now compileOnly on kore-spring's main classpath, so the bean factory method calls DashboardServer(...) directly"
    - "Property-bag adapter pattern (KoreDashboardPropertiesAdapter) for crossing module boundaries without leaking kore-spring types into kore-dashboard — preserves the original direction of the port/adapter dependency"
    - "@ConditionalOnProperty removed from DashboardAutoConfiguration; DashboardServer.isAutoStartup() honours kore.dashboard.enabled at SmartLifecycle level instead. Bean is always created, only the Ktor engine start/stop is gated. Critical for tests that need to inject the bean without binding to a port."
    - "Constructor injection on @SpringBootTest classes via @Autowired-on-class-constructor (the standard idiom in Spring 6 / Kotlin)"
    - "Deferred-items.md log for pre-existing lint failures discovered during a plan but explicitly out of scope per the executor prompt"

key-files:
  created:
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt
    - .planning/phases/03-skills-spring-dashboard/deferred-items.md
  modified:
    - kore-spring/build.gradle.kts
    - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt
    - README.md

key-decisions:
  - "Replaced reflective DashboardServer bridge with direct constructor call now that kore-dashboard is compileOnly on kore-spring's main classpath. The reflective path was a Plan 03-02 expedient — it existed only because kore-dashboard didn't yet exist as a module."
  - "Created KoreDashboardPropertiesAdapter as a private nested class inside KoreAutoConfiguration (not in kore-dashboard) so kore-dashboard stays independent of kore-spring. The adapter implements DashboardServer.DashboardProperties and delegates each accessor to the kore-spring KoreProperties.DashboardProperties data class."
  - "Removed the @ConditionalOnProperty(prefix='kore.dashboard', name='enabled', havingValue='true', matchIfMissing=true) gate from DashboardAutoConfiguration. The property gate prevented bean creation when enabled=false, which broke the test scenario of 'create the bean but don't actually bind to port 8090'. DashboardServer.isAutoStartup() already returns properties.enabled, so SmartLifecycle skips start() when disabled — the bean is created but inert. This is the cleaner separation: bean wiring is unconditional (gated only by class presence), engine startup is gated by the enabled property at the lifecycle level."
  - "KoreIntegrationTest uses @SpringBootTest with constructor injection (via @Autowired on the constructor) rather than field injection. Idiomatic Spring 6 + Kotlin, satisfies CLAUDE.md's no-var rule for the injected dependencies."
  - "Test 1's success criteria check `dashboardServer.isRunning() == false` rather than asserting the Ktor engine is up. The test sets kore.dashboard.enabled=false to keep the JVM port-clean, so verifying the bean exists + lifecycle reports the disabled state is the correct contract — verifying the actual Ktor server start is the job of kore-dashboard's own DashboardRoutingTest, not the kore-spring integration test."
  - "Updated KoreAutoConfigurationTest to set kore.dashboard.enabled=false on both ApplicationContextRunner and the @SpringBootTest. Without this, every existing test in the file flaked with BindException because adding kore-dashboard to the test classpath caused Spring to auto-create the DashboardServer bean and SmartLifecycle started the Ktor engine on port 8090. Multiple tests = port collision."
  - "Updated KoreAutoConfigurationTest's SkillRegistry test from 'expect NoOpSkillRegistry' to 'expect SkillRegistryAdapter'. The original test name said 'when kore-skills is absent' — but kore-skills is now unconditionally on the test classpath (testImplementation), so the @ConditionalOnClass gate fires and SkillsAutoConfiguration wins. The 'kore-skills absent' code path can no longer be tested from inside kore-spring's test source set; documented in the test docstring."

requirements-completed:
  - SPRN-01
  - SPRN-02
  - SPRN-03
  - SPRN-04
  - SKIL-01
  - SKIL-02
  - SKIL-03
  - SKIL-04
  - DASH-01
  - DASH-02
  - DASH-03
  - DASH-04

duration: ~6 min
completed: 2026-04-14
---

# Phase 3 Plan 4: Phase 3 Integration Gate Summary

**Final Phase 3 gate — replaces the reflective DashboardServer bridge in KoreAutoConfiguration with a direct constructor call, lands a full @SpringBootTest integration test exercising every Phase 3 module against a single user-supplied @Bean agent, and adds the README hero demo showing the one-dependency / one-bean / live-dashboard developer experience that has been Phase 3's promise from day one.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-04-14T04:49:57Z
- **Completed:** 2026-04-14T04:56:13Z
- **Tasks:** 1 (TDD-style — integration test + implementation + README in one atomic commit per the plan task definition)
- **Files created:** 2
- **Files modified:** 4

## Accomplishments

- `KoreAutoConfiguration.DashboardAutoConfiguration` no longer uses
  `Class.forName("dev.unityinflow.kore.dashboard.DashboardServer")`. The
  `dashboardServer(...)` bean factory method now calls the
  `DashboardServer(EventBus, AuditLog, DashboardServer.DashboardProperties)`
  primary constructor directly. `@ConditionalOnClass` still guards the inner
  `@Configuration` so hosts that exclude `kore-dashboard` from their runtime
  classpath skip bean creation entirely.
- New `KoreDashboardPropertiesAdapter` (private nested class in
  `KoreAutoConfiguration`) bridges
  `KoreProperties.DashboardProperties` (kore-spring) to
  `DashboardServer.DashboardProperties` (kore-dashboard). The adapter is the
  load-bearing piece keeping the dependency direction one-way:
  `kore-spring → kore-dashboard`, never the reverse.
- `KoreIntegrationTest` boots a full Spring Boot context with `kore-spring`,
  `kore-llm`, `kore-skills`, `kore-dashboard`, and `kore-test` on the test
  classpath, plus a `KoreIntegrationTestApp` scan root containing exactly one
  `@Bean` returning an `AgentRunner` built via the kore DSL backed by
  `MockLLMBackend`. Five tests assert:
    1. `DashboardServer` bean is wired (no reflection in the stack frames).
    2. `SkillRegistryAdapter` replaces `NoOpSkillRegistry` when `kore-skills`
       is on the classpath.
    3. The user `@Bean` returning `agent { }` is injectable as `AgentRunner`
       anywhere in the Spring context.
    4. `KoreActuatorEndpoint.health()` returns `status=UP` through the real
       Spring actuator wiring (not a hand-built endpoint instance).
    5. The full default bean graph is wired alongside the user agent bean.
- Removed `@ConditionalOnProperty(name=["enabled"], havingValue="true",
  matchIfMissing=true)` from `DashboardAutoConfiguration`. The property is
  honoured at the SmartLifecycle level via `DashboardServer.isAutoStartup()`,
  not at bean creation time. This is the cleaner separation:
    - **Bean creation** is gated only by classpath presence
      (`@ConditionalOnClass`) — always create the bean if `DashboardServer` is
      reachable.
    - **Engine startup** is gated by `kore.dashboard.enabled` via
      `isAutoStartup()` — the Ktor CIO engine never binds to a port when the
      property is `false`, but the bean is still injectable for tests and
      manual operations.
- `kore-spring/build.gradle.kts` adds
  `compileOnly(project(":kore-dashboard"))` on the main classpath and
  `testImplementation(project(":kore-dashboard"))`,
  `testImplementation(project(":kore-skills"))`,
  `testImplementation(project(":kore-test"))` on the test classpath. The
  `kore-dashboard` runtime dep stays optional — hosts can exclude it via
  Gradle exclusion if they want a dashboard-less deployment.
- `KoreAutoConfigurationTest` now sets `kore.dashboard.enabled=false` on
  both the `ApplicationContextRunner` and the `KoreAutoConfigurationSpringContextTest`
  to prevent the Ktor CIO engine from binding to port 8090 during unit
  tests. Without this fix, every existing test in the file flaked with
  `BindException` once `kore-dashboard` was added to the test classpath.
- `KoreAutoConfigurationTest`'s SkillRegistry default test was inverted:
  the assertion is now "SkillRegistryAdapter wins" rather than "NoOpSkillRegistry
  is the default", because `kore-skills` is now unconditionally on the
  test classpath. The "absent" path is documented as untestable from
  inside the kore-spring module.
- `README.md` adds "Spring Boot — One Dependency, One Bean, Live Dashboard"
  section showing the Phase 3 hero demo (5 numbered steps): add starter,
  configure `kore.llm.claude.api-key` via `${KORE_CLAUDE_API_KEY}` env var
  (T-03-13), drop a YAML skill in `./kore-skills/code-review.yaml`, register
  a `@Bean` returning `agent { }`, run the app and visit
  `http://localhost:8090/kore` + `http://localhost:8080/actuator/kore`.
- README "Coming in later phases" table updated: `kore-observability`,
  `kore-storage`, `kore-skills`, `kore-dashboard`, and `kore-spring` are
  promoted to the main module table now that Phase 3 ships them.
- New `.planning/phases/03-skills-spring-dashboard/deferred-items.md` logs
  pre-existing lint failures in `kore-llm` and `kore-core/HeroDemoTest.kt`
  that the executor prompt explicitly told me to leave alone.

## Task Commits

1. **Task 1: Wire DashboardAutoConfiguration directly + Phase 3 integration test + README hero demo** — `49131c9` (feat)
   - Drop reflection bridge in `KoreAutoConfiguration`
   - Add `KoreDashboardPropertiesAdapter` private nested class
   - Drop `@ConditionalOnProperty` from `DashboardAutoConfiguration`
   - New `KoreIntegrationTest` + `KoreIntegrationTestApp`
   - Update `KoreAutoConfigurationTest` (dashboard disable property + SkillRegistry test inversion)
   - `kore-spring/build.gradle.kts` adds kore-dashboard compileOnly + 3 test deps
   - README hero demo section + module table promotion

**Plan metadata commit:** (this SUMMARY + STATE/ROADMAP/REQUIREMENTS updates, see final_commit step)

## Files Created/Modified

**Created:**
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt` — full @SpringBootTest with KoreIntegrationTestApp scan root and 5 assertion methods
- `.planning/phases/03-skills-spring-dashboard/deferred-items.md` — pre-existing lint tracking

**Modified:**
- `kore-spring/build.gradle.kts` — `compileOnly(project(":kore-dashboard"))` + 3 testImplementation deps
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` — direct DashboardServer bean factory + `KoreDashboardPropertiesAdapter` + dropped `@ConditionalOnProperty`
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt` — `kore.dashboard.enabled=false` everywhere + SkillRegistry test inversion
- `README.md` — Phase 3 hero demo section + module table promotion

## Decisions Made

See `key-decisions:` block in the frontmatter above. Highlights:

- **Direct constructor over reflection** — the reflective bridge was a Plan 03-02 expedient that only existed because kore-dashboard didn't yet ship as a real module. Now that it does, the direct call is type-safe, readable, and doesn't require the `DashboardProperties` interface to be JVM-erasure-friendly.
- **Adapter direction preserved** — `KoreDashboardPropertiesAdapter` lives in kore-spring (NOT in kore-dashboard) so kore-dashboard never gains a kore-spring compile dependency. The dependency direction stays one-way.
- **`@ConditionalOnProperty` removed from `DashboardAutoConfiguration`** — bean creation and engine startup are now cleanly separated. `isAutoStartup()` (a SmartLifecycle method) honours `kore.dashboard.enabled`; bean creation is gated only on classpath presence. This was the unblocker for the integration test pattern of "create the bean but don't bind to a port".
- **`kore.dashboard.enabled=false` propagated to `KoreAutoConfigurationTest`** — auto-fix Rule 1, see Deviations.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] DashboardServer bean creation gated by `@ConditionalOnProperty`(enabled=true) breaks test injection when enabled=false**

- **Found during:** Task 1, first run of `:kore-spring:test --tests "...KoreIntegrationTest"`
- **Issue:** The plan snippet kept the `@ConditionalOnProperty(name=["enabled"], havingValue="true", matchIfMissing=true)` gate from Plan 03-02. The integration test sets `kore.dashboard.enabled=false` (T-03-14 mitigation: don't bind port 8090 in JVM tests), which caused Spring to skip the entire `DashboardAutoConfiguration` block and the constructor `@Autowired DashboardServer` parameter then resolved to `NoSuchBeanDefinitionException`. All 5 integration tests failed at the parameter-resolution stage.
- **Fix:** Removed the `@ConditionalOnProperty` annotation entirely. The `DashboardServer` SmartLifecycle already honours `kore.dashboard.enabled` via `isAutoStartup()`, so the Ktor engine never binds to a port when the property is `false` — but the bean is now created and injectable. Documented the rationale in a code comment on `DashboardAutoConfiguration`.
- **Files modified:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt`
- **Verification:** All 5 `KoreIntegrationTest` cases pass.
- **Committed in:** `49131c9` (Task 1)

**2. [Rule 1 - Bug] Adding kore-dashboard to test classpath breaks every existing KoreAutoConfigurationTest with BindException on port 8090**

- **Found during:** Task 1, after the integration test passed, running the full `:kore-spring:test` to verify no regressions
- **Issue:** Once `kore-dashboard` was added as a `testImplementation` project dep (so the integration test could see `DashboardServer`), the existing `KoreAutoConfigurationTest`'s `ApplicationContextRunner` (which uses default `application.yml` — no `kore.dashboard.enabled` override) started auto-creating the `DashboardServer` bean. Spring SmartLifecycle then called `start()`, which bound the Ktor CIO engine to port 8090. Multiple tests run sequentially (not parallel inside one JVM) but the second context's start() raced with the first context's still-shutting-down engine, causing a `java.net.BindException` on every test except the first one. Result: 7 of 17 tests failed.
- **Fix:** Added `.withPropertyValues("kore.dashboard.enabled=false")` to the shared `ApplicationContextRunner` in `KoreAutoConfigurationTest`, and added `"kore.dashboard.enabled=false"` to the `@SpringBootTest`'s `properties = [...]` block on `KoreAutoConfigurationSpringContextTest`. With the property false, `DashboardServer.isAutoStartup()` returns false and SmartLifecycle skips `start()` — the bean is created but the Ktor engine is never opened.
- **Files modified:** `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt`
- **Verification:** Full `:kore-spring:test` green (17 tests).
- **Committed in:** `49131c9` (Task 1)

**3. [Rule 1 - Bug] KoreAutoConfigurationTest's SkillRegistry default test asserted NoOpSkillRegistry when SkillRegistryAdapter is now on the test classpath**

- **Found during:** Task 1, after fixing deviation 2, running `:kore-spring:test`
- **Issue:** The plan added `kore-skills` as a `testImplementation` project dep so the integration test could verify `SkillRegistryAdapter` is wired. But the existing `KoreAutoConfigurationTest` had a test asserting `context.getBean(SkillRegistry::class.java) shouldBe NoOpSkillRegistry`. Once `kore-skills` was on the test classpath, the `@ConditionalOnClass` gate on `SkillsAutoConfiguration` fires unconditionally and `SkillRegistryAdapter` wins, so the assertion failed.
- **Fix:** Inverted the test: it now asserts `bean.shouldBeInstanceOf<SkillRegistryAdapter>()` and explicitly comments that the "kore-skills absent" code path cannot be tested from inside the kore-spring module any more (it would require a separate test source set with no kore-skills dep, which is overkill — the absent path is implicit in the `@ConditionalOnClass` annotation). Renamed the test method from `auto-configures NoOpSkillRegistry as the default SkillRegistry bean when kore-skills is absent` to `auto-configures SkillRegistryAdapter as the SkillRegistry bean when kore-skills is on classpath`.
- **Files modified:** `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt`
- **Verification:** All 17 kore-spring tests green.
- **Committed in:** `49131c9` (Task 1)

**4. [Rule 3 - Lint formatting] kotlinter flagged unused import in KoreIntegrationTest.kt after first compile**

- **Found during:** Task 1, after the test passed, running `:kore-spring:lintKotlin`
- **Issue:** `dev.unityinflow.kore.core.AgentTask` was imported but not referenced — the test does not actually call `runner.run(AgentTask(...))`, it only verifies bean wiring.
- **Fix:** `./gradlew :kore-spring:formatKotlin` removed the unused import automatically.
- **Files modified:** `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt`
- **Verification:** `:kore-spring:lintKotlin` clean.
- **Committed in:** `49131c9` (Task 1)

---

**Total deviations:** 4 auto-fixed (3 Rule 1 bugs surfacing as compile/test failures from the new test classpath topology, 1 Rule 3 lint formatting). All four are mechanical consequences of bringing kore-dashboard and kore-skills onto the kore-spring test classpath for the first time. The output contracts (DashboardServer wired without reflection, integration test passes, README hero demo present) match the plan exactly.

### Pre-existing issues out of scope (logged in `deferred-items.md`)

- **`kore-llm:lintKotlinMain` and `kore-llm:lintKotlinTest`** — multiple `multiline-expression-wrapping`, `function-signature`, `chain-method-continuation`, `no-empty-first-line-in-class-body` violations in `OllamaBackend.kt`, `GeminiBackend.kt`, `ClaudeBackendTest.kt`, etc. All committed in Phase 1 / 03-02. The executor prompt explicitly noted that the kore-llm langchain4j version skew should NOT be touched in this plan.
- **`kore-core:lintKotlinTest`** — two formatting violations in `HeroDemoTest.kt:115` and `:117`. Committed in Phase 1 (`825cab3`). The executor prompt explicitly noted that an unrelated stash exists with edits to this file and should be left alone.

Both pre-existing lint failures are tracked in `.planning/phases/03-skills-spring-dashboard/deferred-items.md` for a future cleanup pass.

## Issues Encountered

- The biggest surprise was the `BindException` cascade once `kore-dashboard` joined the kore-spring test classpath — three of the four deviations stem from that single change rippling through the existing test suite. The fix (decouple bean creation from `kore.dashboard.enabled`) is also the cleanest factoring, so the deviation was net-positive.
- The plan's note about `kore.dashboard.port=0` for random port assignment turned out to be unnecessary — disabling the entire SmartLifecycle via `enabled=false` is simpler and exercises the same "bean wired, engine not bound" code path the integration test wants to verify.

## User Setup Required

None.

## Next Phase Readiness

Phase 3 is **complete**. All 12 phase-3 requirement IDs (SPRN-01..04, SKIL-01..04, DASH-01..04) have a passing test or a verified production code path:

- **SPRN-01..04** — `KoreAutoConfigurationTest` (existing) + `KoreIntegrationTest` (new) cover auto-configuration, conditional graph, configuration properties, and the actuator endpoint.
- **SKIL-01..04** — `kore-skills` module + `SkillRegistryAdapter` integration test in `KoreIntegrationTest` (Test 2).
- **DASH-01..04** — `kore-dashboard` module + `DashboardServer` bean wiring verified in `KoreIntegrationTest` (Test 1) and the standalone `kore-dashboard:test` suite from Plan 03-03.

Phase 4 is unblocked. The "one dependency promise" from PROJECT.md is now demonstrable end-to-end via the README hero demo and the integration test.

## Known Stubs

None. The `KoreIntegrationTest` uses `MockLLMBackend` for the test agent's LLM, but that's a deliberate testing primitive (not a stub in production code). The README hero demo uses real `claude()` factory + a real YAML skill file.

## Threat Flags

None. The plan stayed within the existing trust boundary (Spring context with kore beans). The README hero demo references the API key via `${KORE_CLAUDE_API_KEY}` only — never a literal value (T-03-13 mitigation honoured). The integration test sets `kore.dashboard.enabled=false` so the Ktor CIO engine never binds to port 8090 during JVM tests (T-03-14 mitigation honoured).

## Self-Check: PASSED

- [x] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt` FOUND
- [x] `.planning/phases/03-skills-spring-dashboard/deferred-items.md` FOUND
- [x] `kore-spring/build.gradle.kts` FOUND (modified)
- [x] `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` FOUND (modified)
- [x] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt` FOUND (modified)
- [x] `README.md` FOUND (modified)
- [x] Commit `49131c9` FOUND in `git log`
- [x] `Class.forName` reference REMOVED from `KoreAutoConfiguration.kt`
- [x] `DashboardServer(` direct constructor call PRESENT in `KoreAutoConfiguration.kt`
- [x] `KoreDashboardPropertiesAdapter` PRESENT in `KoreAutoConfiguration.kt`
- [x] `KoreIntegrationTest` PRESENT with 5 `@Test` methods
- [x] `kore-spring` in README hero demo section
- [x] `${KORE_CLAUDE_API_KEY}` (env var reference, no literal) PRESENT in README (T-03-13)
- [x] `./kore-skills/code-review.yaml` example PRESENT in README
- [x] `http://localhost:8090/kore` PRESENT in README
- [x] `http://localhost:8080/actuator/kore` PRESENT in README
- [x] `:kore-spring:test` all green (17 tests including 5 new integration tests)
- [x] `./gradlew test` all modules green
- [x] `:kore-spring:lintKotlin :kore-dashboard:lintKotlin :kore-skills:lintKotlin :kore-core:lintKotlinMain` all green
- [x] Phase 3 requirement IDs SPRN-01..04, SKIL-01..04, DASH-01..04 all covered by passing tests or verified artifacts

---
*Phase: 03-skills-spring-dashboard*
*Completed: 2026-04-14*
