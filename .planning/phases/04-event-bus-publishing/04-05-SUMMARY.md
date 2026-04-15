---
phase: 04-event-bus-publishing
plan: 05
subsystem: build-infrastructure
tags: [gradle, maven-publish, signing, nmcp, sonatype-central-portal, buildSrc, convention-plugin]
dependency-graph:
  requires:
    - 04-01  # nmcp version alias in libs.versions.toml + kore-kafka/kore-rabbitmq skeletons
    - 04-02  # kore-kafka module
    - 04-03  # kore-rabbitmq module
    - 04-04  # kore-spring auto-config wiring (unblocks kore-spring publication)
  provides:
    - buildSrc/kore.publishing convention plugin
    - Root nmcp aggregation wiring
    - publishToMavenLocal green for all 11 modules
  affects:
    - build.gradle.kts
    - buildSrc/
    - every kore-*/build.gradle.kts
tech-stack:
  added:
    - com.gradleup.nmcp:nmcp:1.4.4 (buildSrc classpath)
    - com.gradleup.nmcp plugin applied to all 11 modules via convention plugin
    - com.gradleup.nmcp.aggregation plugin applied to root
    - java-library plugin applied via convention plugin (coexists with kotlin-jvm)
    - signing plugin with useInMemoryPgpKeys
  patterns:
    - buildSrc precompiled script convention plugin (Pattern 7)
    - Pitfall 4 defense: full POM template in convention plugin, module-specific overrides
    - Pitfall 5 defense: sign(publishing.publications["maven"]) — publication, not task
    - Pitfall 11 defense: --no-configuration-cache for signing env-var re-read
    - Pitfall 13 defense: explicit 11-module nmcpAggregation list
key-files:
  created:
    - buildSrc/settings.gradle.kts
    - buildSrc/build.gradle.kts
    - buildSrc/src/main/kotlin/kore.publishing.gradle.kts
  modified:
    - build.gradle.kts
    - kore-core/build.gradle.kts
    - kore-llm/build.gradle.kts
    - kore-mcp/build.gradle.kts
    - kore-observability/build.gradle.kts
    - kore-storage/build.gradle.kts
    - kore-skills/build.gradle.kts
    - kore-spring/build.gradle.kts
    - kore-dashboard/build.gradle.kts
    - kore-test/build.gradle.kts
    - kore-kafka/build.gradle.kts
    - kore-rabbitmq/build.gradle.kts
key-decisions:
  - "buildSrc precompiled script plugin path chosen over inline per-module publishing config — single source of truth for POM template, easier to audit Pitfall-4 compliance"
  - "java-library applied inside convention plugin (not relying on kotlin-jvm from consumer) so the `java { }` extension and components[\"java\"] are visible at convention-plugin compile time; coexists cleanly with kotlin-jvm in every module"
  - "Root applies id(\"com.gradleup.nmcp.aggregation\") WITHOUT a version (not via alias) because the nmcp plugin marker is already on the build classpath via buildSrc, and Gradle rejects `alias(...)` with unknown-classpath-version conflict"
  - "Signing guarded by `if (signingKey != null && signingPassword != null)` so local `publishToMavenLocal` succeeds without GPG keys; CI release workflow (04-06) provides them via SIGNING_KEY/SIGNING_PASSWORD env vars"
  - "publishingType = \"USER_MANAGED\" so Sonatype Central staging bundle requires manual portal review before release (Pitfall 4 belt-and-braces)"
metrics:
  duration: 5min
  completed: 2026-04-15
---

# Phase 4 Plan 5: buildSrc convention plugin + nmcp aggregation Summary

Shared `kore.publishing` Gradle convention plugin in `buildSrc` with full POM template, in-memory GPG signing, and `com.gradleup.nmcp` application; root `build.gradle.kts` wires `nmcp.aggregation` with all 11 publishable modules; `publishToMavenLocal` produces complete artifact sets (jar + sources + javadoc + pom) for every module.

## What Was Built

Plan 04-05 establishes the Maven Central publishing infrastructure for the entire kore-runtime ecosystem. After this plan, the final release workflow (04-06) only needs to bump the version, set env-var secrets, and invoke `publishAggregationToCentralPortal`.

### Task 1 — `buildSrc/kore.publishing` convention plugin (commit `d24b167`)

Created a precompiled script plugin at `buildSrc/src/main/kotlin/kore.publishing.gradle.kts` that:

- Applies `java-library`, `maven-publish`, `signing`, and `com.gradleup.nmcp`.
- Configures `java { withSourcesJar(); withJavadocJar() }` so every module emits both required Sonatype jars (empty javadoc jar is acceptable for v0.0.1 Kotlin — Sonatype only requires the jar exist, not that it contain javadoc).
- Creates a single `MavenPublication("maven")` from `components["java"]` with a full default POM template covering all six Sonatype-required fields:
  - `name` (default = project name, OVERRIDDEN per module)
  - `description` (default generic, OVERRIDDEN per module)
  - `url` → `https://github.com/UnityInFlow/kore`
  - `licenses` → MIT with repo distribution
  - `developers` → Jiří Hermann (id `jhermann`, email `jiri@unityinflow.dev`, org `UnityInFlow`)
  - `scm` → standard `scm:git:https://...` + `scm:git:ssh://...` URLs
  - `issueManagement` → GitHub issues URL
- Configures `signing { }` to read `SIGNING_KEY` + `SIGNING_PASSWORD` via `providers.environmentVariable(...)`. If either is `null` (local dev), signing is skipped and a lifecycle log message explains why — `publishToMavenLocal` still succeeds. When both are present (CI release workflow), the plugin calls `useInMemoryPgpKeys(signingKey, signingPassword)` followed by `sign(publishing.publications["maven"])`.

`buildSrc/settings.gradle.kts` binds the root `gradle/libs.versions.toml` version catalog under the `libs` name so future buildSrc expansions can reference shared versions. `buildSrc/build.gradle.kts` pulls in `com.gradleup.nmcp:nmcp:1.4.4` as the only external dependency (kotlin-dsl plugin provides the rest).

### Task 2 — Root aggregation + per-module wiring (commit `c0ae9e3`)

Root `build.gradle.kts`:

- Applies `id("com.gradleup.nmcp.aggregation")` (no version — see Deviations).
- Preserves existing `subprojects { }` block (kotlinter, group, version, mavenCentral).
- Adds `nmcpAggregation { centralPortal { ... } }` block wiring `username`/`password` env-var providers, `publishingType = "USER_MANAGED"` (manual staging review), and `publicationName = "kore-${project.version}"`.
- Declares exactly 11 `nmcpAggregation(project(":kore-*"))` dependency lines — one per publishable module (Pitfall 13 defense: grep-verifiable count). `grep -c "nmcpAggregation(project(" build.gradle.kts` returns `11`.

Each of the 11 modules (`kore-core`, `kore-llm`, `kore-mcp`, `kore-observability`, `kore-storage`, `kore-skills`, `kore-spring`, `kore-dashboard`, `kore-test`, `kore-kafka`, `kore-rabbitmq`) gained two additions:

1. `id("kore.publishing")` added to its `plugins { }` block (after kotlin-jvm / kotlinter / spring-dep-mgmt / kotlin-serialization, wherever applicable).
2. A trailing `publishing { publications { named<MavenPublication>("maven") { pom { name.set(...); description.set(...) } } } }` block with module-specific metadata from the plan's name/description table:

| Module | POM name | POM description (abridged) |
|---|---|---|
| kore-core | `kore-runtime — Core` | Core agent loop, DSL, sealed-class result types, EventBus port |
| kore-llm | `kore-runtime — LLM backends` | Claude, GPT, Ollama, Gemini LLMBackend adapters |
| kore-mcp | `kore-runtime — MCP` | MCP client + server adapters |
| kore-observability | `kore-runtime — Observability` | OTel span + Micrometer metric observers |
| kore-storage | `kore-runtime — Storage` | PostgreSQL audit log via Exposed + Flyway |
| kore-skills | `kore-runtime — Skills` | YAML skill loader with pattern-activation matching |
| kore-spring | `kore-runtime — Spring Boot` | Spring Boot 4 auto-configuration starter |
| kore-dashboard | `kore-runtime — Dashboard` | HTMX admin dashboard |
| kore-test | `kore-runtime — Test` | MockLLMBackend + session recording/replay |
| kore-kafka | `kore-runtime — Kafka EventBus adapter` | Opt-in Apache Kafka EventBus implementation |
| kore-rabbitmq | `kore-runtime — RabbitMQ EventBus adapter` | Opt-in RabbitMQ EventBus implementation |

## Verification

```
$ ./gradlew clean publishToMavenLocal --no-configuration-cache --no-parallel
BUILD SUCCESSFUL in 17s
101 actionable tasks: 92 executed, 9 up-to-date

$ ls -d ~/.m2/repository/dev/unityinflow/kore-*/0.0.1-SNAPSHOT | wc -l
11

$ for m in kore-core kore-llm kore-mcp kore-observability kore-storage \
           kore-skills kore-spring kore-dashboard kore-test \
           kore-kafka kore-rabbitmq; do
    ls ~/.m2/repository/dev/unityinflow/$m/0.0.1-SNAPSHOT/ | grep -v md5\|sha
  done
# Every module: {jar, -sources.jar, -javadoc.jar, .pom, .module, maven-metadata-local.xml}

$ grep -E "<(name|description|url|licenses|developers|scm|issueManagement)>" \
    ~/.m2/.../kore-core-0.0.1-SNAPSHOT.pom
  <name>kore-runtime — Core</name>
  <description>Core agent loop, DSL, sealed-class result types, and EventBus port ...</description>
  <url>https://github.com/UnityInFlow/kore</url>
  <licenses>...
  <developers>...
  <scm>...
  <issueManagement>...

$ ./gradlew tasks --group publishing --no-configuration-cache | grep publishAggregation
  publishAggregationToCentralPortal - Publishes the aggregation to the Central Releases repository.
  publishAggregationToCentralPortalSnapshots
  publishAggregationToCentralSnapshots

$ grep -c "nmcpAggregation(project(" build.gradle.kts
11
```

All eight `<done>` criteria from Task 1 and all eight `<done>` criteria from Task 2 pass. `publishToMavenLocal` runs in ~17 seconds end-to-end from clean state.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added `java-library` plugin to convention plugin**

- **Found during:** Task 1 first `./gradlew help` after creating `kore.publishing.gradle.kts`.
- **Issue:** Convention plugin compilation failed with `Unresolved reference 'java'` / `withSourcesJar` / `withJavadocJar`. The `maven-publish` plugin alone does NOT bring the `java { }` extension onto the precompiled script plugin's compile classpath — `kotlin-jvm` is applied per-module (later than the convention plugin loads) and cannot be assumed at buildSrc compile time.
- **Fix:** Added `` `java-library` `` to the convention plugin's `plugins { }` block. The plan originally assumed kotlin-jvm's implicit `java-base` application would be visible; in precompiled script plugins the plugin application order is `convention plugin first, consumer plugins second`, so the `java` extension must be applied at convention-plugin level.
- **Impact:** None at consumer level. `java-library` coexists with `kotlin-jvm` in every module (kotlin-jvm layers on top of java-base and uses the same `components["java"]`).
- **Files modified:** `buildSrc/src/main/kotlin/kore.publishing.gradle.kts`
- **Commit:** `d24b167`

**2. [Rule 3 - Blocking] Applied `com.gradleup.nmcp.aggregation` without a version alias in root**

- **Found during:** Task 2 first `./gradlew clean publishToMavenLocal`.
- **Issue:** Gradle rejected `alias(libs.plugins.nmcp.aggregation)` in the root `plugins { }` block with `"The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version, so compatibility cannot be checked."` Root cause: the buildSrc `build.gradle.kts` depends on `com.gradleup.nmcp:nmcp:1.4.4` (so the convention plugin can apply `id("com.gradleup.nmcp")`). That JAR contains both the `nmcp` and `nmcp-aggregation` plugin implementations. Because buildSrc is applied before any subproject and its classpath is added to the root script classpath, the aggregation plugin is technically "on the classpath", but without version metadata — Gradle refuses to cross-check a versioned `alias(...)` request.
- **Fix:** Replaced `alias(libs.plugins.nmcp.aggregation)` with `id("com.gradleup.nmcp.aggregation")` (unversioned). Gradle resolves this to the same classpath copy. Version pinning is unchanged — buildSrc still pins `1.4.4` via its `implementation(...)` dependency, and `libs.versions.toml` retains both plugin aliases for documentation + future use if we ever refactor buildSrc out.
- **Impact:** None on version pinning or reproducibility. The plugin alias in `libs.versions.toml` is intentionally kept (not removed) so `gradle/libs.versions.toml` remains the single source of truth for the nmcp version.
- **Files modified:** `build.gradle.kts`
- **Commit:** `c0ae9e3`

## Authentication Gates

None. This plan is local-only (no Sonatype upload, no network calls). The release CI workflow in plan 04-06 handles env-var secrets and the portal handshake.

## Pitfall Defenses Summary

| Pitfall | Defense Location | Verification |
|---|---|---|
| Pitfall 4 — POM validation rejection | Full POM template in convention plugin; every module overrides `name` + `description` | `grep -E "<(name\|description\|url\|licenses\|developers\|scm\|issueManagement)>"` on any module POM |
| Pitfall 5 — Signing the task instead of publication | `sign(publishing.publications["maven"])` inside convention plugin | `grep 'sign(publishing.publications\["maven"\])' buildSrc/src/main/kotlin/kore.publishing.gradle.kts` |
| Pitfall 11 — Configuration cache + env-var providers | `--no-configuration-cache` flag used for both local publish and CI release (documented in KDoc of convention plugin) | Acceptance criterion in `<done>` |
| Pitfall 13 — Empty `nmcpAggregation` deps | Explicit 11-line declaration block in root `build.gradle.kts` | `grep -c "nmcpAggregation(project(" build.gradle.kts` returns 11 |

## Threat Model Reference

From plan frontmatter threat register:

- **T-04-06 (Tampering at Central Portal due to missing POM fields):** mitigated. Convention plugin emits all six Sonatype-required POM elements on every module. Verified by inspecting `kore-core-0.0.1-SNAPSHOT.pom`.
- **T-04-07 (EoP via GPG key leakage in build logs):** mitigated. `useInMemoryPgpKeys` does not print the key; `providers.environmentVariable` is read at execution time under `--no-configuration-cache`. Convention plugin logs only a "signing skipped" message when env vars are absent — never the values.
- **T-04-09 (Repudiation of artifact provenance):** defended by Pitfall 5 mitigation above. When CI sets signing env vars, every artifact in the publication gets a sibling `.asc` file.
- **T-04-13 (Empty aggregation bundle):** defended by Pitfall 13 mitigation above.

T-04-03 (Sonatype namespace verification) is a pre-flight human task for plan 04-06, not this plan.

## Known Stubs

None. Every module publishes a complete artifact set to local Maven. The only remaining step for real Maven Central release is to (a) bump version `0.0.1-SNAPSHOT → 0.0.1`, (b) set the four org-level secrets in GitHub, and (c) push a `v0.0.1` tag — all of which is plan 04-06's scope.

## Follow-ups for Plan 04-06

1. Bump root `version = "0.0.1-SNAPSHOT"` → `"0.0.1"` (single-line edit).
2. Create `.github/workflows/release.yml` triggered on `v*.*.*` tag pushes, using `runs-on: [arc-runner-unityinflow]`, invoking `./gradlew publishAggregationToCentralPortal --no-configuration-cache`.
3. Confirm pre-flight PF-01..PF-04 (Sonatype namespace verification, repo visibility/runner group, Docker on runners, org-level secrets) before first tag push.
4. After successful release, bump to `0.0.2-SNAPSHOT` on main per D-06.

## Self-Check: PASSED

- FOUND: `buildSrc/settings.gradle.kts` (committed `d24b167`)
- FOUND: `buildSrc/build.gradle.kts` (committed `d24b167`)
- FOUND: `buildSrc/src/main/kotlin/kore.publishing.gradle.kts` (committed `d24b167`)
- FOUND: `d24b167` in `git log`
- FOUND: `c0ae9e3` in `git log`
- FOUND: `sign(publishing.publications["maven"])` in convention plugin
- FOUND: `id("kore.publishing")` in all 11 `kore-*/build.gradle.kts` files (grep count = 11)
- FOUND: `nmcpAggregation(project(` count = 11 in root `build.gradle.kts`
- FOUND: `publishAggregationToCentralPortal` task in Gradle task list
- FOUND: 11 module dirs under `~/.m2/repository/dev/unityinflow/` each containing jar + sources + javadoc + pom
