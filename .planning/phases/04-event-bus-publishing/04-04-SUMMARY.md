---
phase: 04-event-bus-publishing
plan: 04
subsystem: infra
tags: [spring-boot, auto-configuration, kafka, rabbitmq, event-bus, conditional-bean, kotlinx-coroutines]

# Dependency graph
requires:
  - phase: 04-event-bus-publishing
    provides: "kore-kafka KafkaEventBus adapter (04-02), kore-rabbitmq RabbitMqEventBus adapter (04-03)"
  - phase: 03-skills-spring-dashboard
    provides: "KoreAutoConfiguration + KoreProperties skeleton (03-02), @ConditionalOnClass(name=[...]) string form pattern, Pitfall 12 defense"
provides:
  - "KafkaEventBusAutoConfiguration inner @Configuration class gated by @ConditionalOnClass(name=['dev.unityinflow.kore.kafka.KafkaEventBus']) + @ConditionalOnProperty(havingValue='kafka')"
  - "RabbitMqEventBusAutoConfiguration mirror-image for kore-rabbitmq"
  - "KoreProperties.EventBusProperties nested config binding kore.event-bus.type + kore.event-bus.kafka.* + kore.event-bus.rabbitmq.*"
  - "Top-level toAdapterConfig() extension functions on KafkaProperties and RabbitMqProperties mapping to adapter-native config types"
  - "koreEventBusScope CoroutineScope bean (SupervisorJob + Dispatchers.IO.limitedParallelism(4)), conditional on type=kafka or type=rabbitmq"
  - "Spring context tests using assertThat(ctx).hasBean(...) definition-level assertion to avoid eager bean resolution (Kafka adapter opens real broker socket at construction)"
affects: [04-publishing-release, v0.0.1-release]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@ConditionalOnClass(name=[fqn-string]) + @ConditionalOnProperty(havingValue=exact-string) + @ConditionalOnMissingBean(EventBus::class) triple gate for opt-in adapter wiring"
    - "destroyMethod='close' on adapter @Bean for AutoCloseable graceful shutdown at Spring context teardown"
    - "assertThat(ctx).hasBean(String) instead of ctx.getBean(...) or ctx.beanDefinitionNames.toList() — definition-level assertion bypasses factory invocation when bean construction has side effects"
    - "Parallel inner @Configuration classes for koreEventBusScope (one per havingValue) as simpler substitute for @ConditionalOnExpression disjunction"
    - "compileOnly(project(':kore-kafka')) + testImplementation(project(':kore-kafka')) — main classpath stays opt-in while tests evaluate the @ConditionalOnClass gate"

key-files:
  created:
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/RabbitMqEventBusAutoConfigurationTest.kt
  modified:
    - kore-spring/build.gradle.kts
    - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt
    - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt

key-decisions:
  - "assertThat(ctx).hasBean(beanName) over ctx.getBean(EventBus::class.java) for Kafka bean — KafkaEventBus(config, scope) opens a real KafkaProducer+KafkaConsumer against bootstrap servers at construction, so bean resolution would fail context refresh"
  - "Same hasBean assertion applied to RabbitMQ test for consistency even though RabbitMqEventBus has lazy connection — defense-in-depth against future refactors that might move the lazy boundary"
  - "compileOnly(project(':kore-kafka')) + compileOnly(project(':kore-rabbitmq')) preserves opt-in semantics (Pitfall 3) — consumers MUST explicitly add the adapter modules, kore-spring does not transitively pull them"
  - "Two parallel KafkaEventBusScopeConfiguration + RabbitMqEventBusScopeConfiguration inner classes instead of a single @ConditionalOnExpression disjunction — simpler, each @ConditionalOnProperty stays explicit, only one fires per context"
  - "destroyMethod='close' on both adapter @Bean methods so Spring calls AutoCloseable.close() at context shutdown, cancelling the consumer loop and closing the producer/channel gracefully"
  - "top-level toAdapterConfig() extension functions (not member functions) on KoreProperties.KafkaProperties / RabbitMqProperties so KoreProperties.kt stays decoupled from adapter config types at the data-class level — only the mapping extensions depend on the compileOnly kore-kafka / kore-rabbitmq classes"

patterns-established:
  - "Opt-in adapter auto-configuration: @ConditionalOnClass(name=[string-fqn]) + @ConditionalOnProperty(havingValue=exact) + @ConditionalOnMissingBean(EventBus::class) + destroyMethod='close'"
  - "Spring context testing for beans with side-effectful construction: AssertableApplicationContext.hasBean(String) — bean DEFINITION presence without triggering factory method invocation"
  - "Property-binding tests for nested @ConfigurationProperties data classes use constructor-level KoreProperties(eventBus=...) calls rather than a full Spring environment"

requirements-completed: [EVNT-03, EVNT-04]

# Metrics
duration: 12min
completed: 2026-04-13
---

# Phase 04 Plan 04: kore-spring auto-configuration wiring for Kafka + RabbitMQ event buses Summary

**Two new Spring Boot @AutoConfiguration inner classes in KoreAutoConfiguration.kt that switch the EventBus bean to KafkaEventBus or RabbitMqEventBus via `kore.event-bus.type={kafka,rabbitmq}` in application.yml, with zero transitive runtime deps and Spring context tests that never open a real broker socket.**

## Performance

- **Duration:** ~12 min
- **Tasks:** 2 (TDD: RED test + GREEN impl)
- **Files modified:** 4
- **Files created:** 2

## Accomplishments

- Added `KoreProperties.EventBusProperties` nested binding with `KafkaProperties` and `RabbitMqProperties` sub-classes and default `type = "in-process"`.
- Added top-level `KafkaProperties.toAdapterConfig()` and `RabbitMqProperties.toAdapterConfig()` extension functions mapping to kore-kafka / kore-rabbitmq native config types.
- Added `KafkaEventBusAutoConfiguration` + `RabbitMqEventBusAutoConfiguration` inner classes with `@ConditionalOnClass(name=[...])` string form (Pitfall 2 defense), explicit `havingValue="kafka"` / `"rabbitmq"` (Pitfall 8 defense), and `@ConditionalOnMissingBean(EventBus::class)` to preserve user-override precedence.
- Added `KafkaEventBusScopeConfiguration` + `RabbitMqEventBusScopeConfiguration` parallel inner classes registering a shared `koreEventBusScope` CoroutineScope bean scoped to `SupervisorJob() + Dispatchers.IO.limitedParallelism(4)` only when an adapter is selected.
- Added `destroyMethod = "close"` on both adapter `@Bean` methods so Spring calls `AutoCloseable.close()` at context shutdown — graceful producer/consumer shutdown per D-01/D-02.
- Extended `KorePropertiesTest` with 4 new tests (default binding, custom eventBus type override, KafkaProperties.toAdapterConfig mapping, RabbitMqProperties.toAdapterConfig mapping).
- Created `KafkaEventBusAutoConfigurationTest` with 3 Spring context tests using `assertThat(ctx).hasBean("kafkaEventBus")` definition-level assertion — the critical pattern that avoids invoking the KafkaEventBus factory (which would open a real TCP socket to `localhost:9092`).
- Created `RabbitMqEventBusAutoConfigurationTest` mirror-image with the same `hasBean` pattern.
- `kore-spring/build.gradle.kts` adds `compileOnly(project(":kore-kafka"))` + `compileOnly(project(":kore-rabbitmq"))` for main classpath opt-in semantics, plus matching `testImplementation` for both so the Spring context tests can evaluate the `@ConditionalOnClass` gates.

## Task Commits

1. **Task 1: Add EventBusProperties nesting + failing Spring context tests (RED)** — `c28eff2` (test)
2. **Task 2: Add KafkaEventBusAutoConfiguration + RabbitMqEventBusAutoConfiguration to KoreAutoConfiguration, make tests GREEN** — `eeb77c5` (feat)

## Files Created/Modified

- `kore-spring/build.gradle.kts` — Added `compileOnly` + `testImplementation` for `kore-kafka` and `kore-rabbitmq`.
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt` — Added `eventBus` field to primary constructor; added `EventBusProperties`, `KafkaProperties`, `RabbitMqProperties` nested data classes; added top-level `toAdapterConfig()` extension functions.
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` — Added 4 new inner `@Configuration` classes: `KafkaEventBusScopeConfiguration`, `RabbitMqEventBusScopeConfiguration`, `KafkaEventBusAutoConfiguration`, `RabbitMqEventBusAutoConfiguration`. Updated top-level KDoc bullet list with kore-kafka / kore-rabbitmq entries. Added `kotlinx.coroutines.{CoroutineScope, Dispatchers, SupervisorJob}` + `org.springframework.beans.factory.annotation.Qualifier` imports.
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt` — 3 tests: type=kafka wires kafkaEventBus bean (hasBean assertion), type=in-process keeps InProcessEventBus, type absent keeps InProcessEventBus.
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/RabbitMqEventBusAutoConfigurationTest.kt` — 2 tests: type=rabbitmq wires rabbitMqEventBus bean, type=in-process keeps InProcessEventBus.
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt` — 4 new tests: eventBus default binding, custom type override, KafkaProperties.toAdapterConfig mapping, RabbitMqProperties.toAdapterConfig mapping.

## Decisions Made

- **`hasBean(String)` over `getBean(Class)` for Kafka case:** The iteration-2 context note flagged the eager-instantiation hole where `ctx.getBean(EventBus::class.java)` would invoke the `@Bean` factory and construct a real `KafkaProducer` / `KafkaConsumer` against `localhost:9092`. `AssertableApplicationContext.hasBean(String)` inspects bean definition registry without triggering resolution.
- **Parallel scope configurations:** Instead of `@ConditionalOnExpression("'${kore.event-bus.type}'=='kafka' or '${kore.event-bus.type}'=='rabbitmq'")` (SpEL disjunction — harder to audit), two parallel `@Configuration` classes each gated by a single `havingValue`. Only one fires per context, both register the same bean name `koreEventBusScope`.
- **`destroyMethod = "close"` on adapter beans:** Spring Boot's default lifecycle calls `AutoCloseable.close()` at context shutdown when this attribute is set. Without it, the consumer loop coroutine would leak after context teardown. Both `KafkaEventBus` and `RabbitMqEventBus` already implement `AutoCloseable` (confirmed in adapter source).
- **Top-level extension functions (not member functions) for `toAdapterConfig()`:** Keeps `KoreProperties.kt` decoupled — the data classes themselves reference only stdlib types, the extension functions (separate top-level declarations at the bottom of the file) handle the mapping to `compileOnly` kore-kafka / kore-rabbitmq types. If a consumer ever wanted to reuse `KoreProperties.KafkaProperties` without kore-kafka on the classpath, they could — the member data class compiles independently of the extensions.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- **Plan action step 6 expected `compileTestKotlin` to fail in RED state.** Because the test files reference `hasBean("kafkaEventBus")` string-only (no class literal reference) and `KoreProperties.EventBusProperties` was added to main in the same Task 1 commit, compileTestKotlin actually succeeded. The RED signal came from `:kore-spring:test` producing 2 assertion failures at runtime ("Assertion error: expected 'kafkaEventBus' bean to be present"), which is equivalent proof of RED state — the tests fail, exactly as required by TDD. Proceeded without modifying the plan; the iteration-2 H2 fix (using `hasBean`) is the correct pattern.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- EVNT-03 + EVNT-04 fully closed end-to-end: adapter modules (04-02, 04-03) plus Spring auto-configuration wiring (this plan).
- Ready for plan 04-05 (EVNT-01/EVNT-02 InProcessEventBus validation tests) and plan 04-06 (Maven Central publishing).
- No blockers introduced.

## Self-Check: PASSED

**Created files verified:**
- FOUND: kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt
- FOUND: kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/RabbitMqEventBusAutoConfigurationTest.kt

**Modified files verified:**
- FOUND: kore-spring/build.gradle.kts (added compileOnly + testImplementation for kore-kafka/kore-rabbitmq)
- FOUND: kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt (EventBusProperties + toAdapterConfig)
- FOUND: kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt (4 new inner @Configuration classes)
- FOUND: kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt (4 new tests)

**Commits verified:**
- FOUND: c28eff2 test(04-04): add failing KoreProperties EventBus + Spring context tests
- FOUND: eeb77c5 feat(04-04): wire KafkaEventBus and RabbitMqEventBus auto-configurations in KoreAutoConfiguration

**Verification gates passed:**
- `./gradlew :kore-spring:test` — exit 0, all KafkaEventBus/RabbitMq context tests GREEN
- `./gradlew :kore-spring:lintKotlin` — exit 0, clean
- `./gradlew :kore-skills:test :kore-dashboard:test :kore-spring:test` — no Phase 3 regression
- `grep -n "@ConditionalOnClass(KafkaEventBus::class)|@ConditionalOnClass(RabbitMqEventBus::class)"` — zero matches (Pitfall 2 defense holds)
- `grep -n "beanDefinitionNames"` in kore-spring/src/test — zero matches (anti-pattern absent)

---
*Phase: 04-event-bus-publishing*
*Completed: 2026-04-13*
