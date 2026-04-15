---
phase: 04-event-bus-publishing
plan: 06
subsystem: release-infrastructure
status: partial
tags: [release, ci, github-actions, sonatype-central-portal, v0.0.1, partial, blocked-on-human]
dependency-graph:
  requires:
    - 04-05  # buildSrc convention plugin + nmcp aggregation
  provides:
    - .github/workflows/release.yml
    - .github/workflows/release-dry-run.yml
    - docs/RELEASE-CHECKLIST.md
    - build.gradle.kts @ version = "0.0.1"
  affects:
    - GitHub repo creation (pending)
    - Sonatype namespace claim (pending)
    - Org-level secrets (pending)
    - Maven Central publication (pending)
tech-stack:
  added:
    - softprops/action-gh-release@v2
    - gradle/actions/setup-gradle@v4 (already on ci.yml)
  patterns:
    - Tag-triggered release workflow (v*.*.*)
    - Manual dry-run workflow (workflow_dispatch) as pre-flight safety net
    - Copy-pasteable PF-00..PF-05 human checklist for first-ever release
key-files:
  created:
    - .github/workflows/release.yml
    - .github/workflows/release-dry-run.yml
    - docs/RELEASE-CHECKLIST.md
    - .planning/phases/04-event-bus-publishing/deferred-items.md
  modified:
    - build.gradle.kts
key-decisions:
  - "Release workflow uses runs-on: [arc-runner-unityinflow] (no X64 AND-pin) per CLAUDE.md default — PF-02 runner label audit confirms every label-matching runner is already Hetzner X64"
  - "Env vars passed as plain SIGNING_KEY/SIGNING_PASSWORD (not ORG_GRADLE_PROJECT_ prefixed) because the buildSrc kore.publishing convention plugin reads them via providers.environmentVariable(...) directly (see buildSrc/src/main/kotlin/kore.publishing.gradle.kts:104-105)"
  - "--no-configuration-cache on BOTH the build step and the publish step — defends against Pitfall 11 env-var cache staleness on every Gradle invocation, not just the publish step"
  - "release-dry-run workflow is workflow_dispatch-only, unsigned, and runs publishToMavenLocal — intentionally does NOT touch Sonatype so the pre-flight can succeed without any org secrets configured"
  - "Integration tests remain excluded from the release path — release.yml runs default ./gradlew build which excludes @Tag(\"integration\") tests per Wave 2 convention; Docker availability on runners is not a release blocker"
  - "PARTIAL status: Tasks 1+2 (workflow + version bump + checklist) are committed; Tasks 3-5 (RC tag, final release, 0.0.2-SNAPSHOT bump) are blocked on PF-00..PF-05 human pre-flight work"
metrics:
  duration: 20min
  completed: 2026-04-15
  status: partial
---

# Phase 4 Plan 6: v0.0.1 Release — Phase A (PARTIAL)

> **STATUS: PARTIAL** — Autonomous artifacts landed on `main`; remaining
> release steps (RC dry-run, tag push, Sonatype manual publish, post-release
> version bump) are blocked on human pre-flight (PF-00..PF-05). See
> `docs/RELEASE-CHECKLIST.md` for the exact resume steps.

The four autonomous artifacts required to ship kore-runtime v0.0.1 to Maven
Central are now on `main`. The release workflow, the safety-net dry-run
workflow, the root project version bump to `0.0.1`, and the human-facing
release checklist are all committed. What remains is human-only work: create
the GitHub repo, verify the Sonatype namespace, generate the GPG key, set
the four org-level secrets, then press a few buttons.

## What Was Built (Phase A)

### Artifact 1 — Root version bump to `0.0.1` (commit `f81ee03`)

Single-line change in root `build.gradle.kts`:

```diff
-    version = "0.0.1-SNAPSHOT" // Bumped to "0.0.1" in plan 04-06 at release time
+    version = "0.0.1" // Bumped from 0.0.1-SNAPSHOT in plan 04-06 Phase A for v0.0.1 release
```

The `subprojects { }` block now publishes `dev.unityinflow:kore-*:0.0.1`
artifacts locally and (once the release workflow runs) to the Sonatype
Central Portal staging bundle. The inline comment is a trail marker for
plan 04-06 Phase B when the post-release bump to `0.0.2-SNAPSHOT` happens.

### Artifact 2 — `.github/workflows/release.yml` (commit `f880c80`)

Tag-triggered release workflow. Key shape:

- **Trigger:** `push.tags: ['v*.*.*']` — ONLY on v-tag push, never on branch
  push or pull request.
- **Runner:** `runs-on: [arc-runner-unityinflow]` (no X64 AND-pin). The
  orchestrator reconnaissance confirmed all three Hetzner runners carry this
  label and are online, and the orangepi ARM runner does NOT — exactly the
  PF-02 runner label audit precondition.
- **Permissions:** `contents: write` (required by
  `softprops/action-gh-release@v2` to create the GitHub Release).
- **Steps:**
  1. `actions/checkout@v4` with `fetch-depth: 0` — needed for the
     auto-generated GitHub Release notes (T-04-09 provenance).
  2. `actions/setup-java@v4` — Temurin 21.
  3. `gradle/actions/setup-gradle@v4` — cache + wrapper validation (matches
     the existing ci.yml convention).
  4. `./gradlew clean build --no-configuration-cache` — full lint + compile +
     unit test sweep across all 11 modules. Configuration cache disabled to
     avoid Pitfall 11 interactions with env-var providers downstream.
  5. `./gradlew publishAggregationToCentralPortal --no-configuration-cache`
     with all four secrets injected as env vars:
     ```yaml
     env:
       SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
       SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
       SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
       SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
     ```
     Plain env var names (not `ORG_GRADLE_PROJECT_*` prefixed) because the
     `kore.publishing` convention plugin from plan 04-05 reads them via
     `providers.environmentVariable("SIGNING_KEY")` etc. at
     `buildSrc/src/main/kotlin/kore.publishing.gradle.kts:104-105`.
  6. `softprops/action-gh-release@v2` with `generate_release_notes: true`,
     `draft: false`, `prerelease: false`, and a body that embeds the
     installation snippet + USER_MANAGED staging disclaimer.

Embedded threat-model comments at the top of the file document:

- **T-04-07** — GPG key leakage: `--no-configuration-cache` defends against
  env-var serialization into cache files; GitHub Actions masks secrets in
  logs automatically.
- **T-04-10** — runner trust boundary: the self-hosted runner pool is the
  trust boundary; only org admins can modify it. Human RC dry-run catches
  anomalous bundles downstream.
- **Pitfall 10** — runner label correctness: no explicit X64 AND-pin because
  the label already resolves only to X64 Hetzner.

YAML validated via `python3 -c "import yaml; yaml.safe_load(open(...))"`.

### Artifact 3 — `.github/workflows/release-dry-run.yml` (commit `b87d60a`)

Safety-net workflow for pre-release verification:

- **Trigger:** `workflow_dispatch` (manual "Run workflow" button).
- **Runner:** `runs-on: [arc-runner-unityinflow]`.
- **No secrets required** — this workflow NEVER uploads to Sonatype and
  NEVER signs.
- **Steps:**
  1. Checkout + JDK 21 + Gradle setup.
  2. `./gradlew clean build --no-configuration-cache`.
  3. `./gradlew publishToMavenLocal --no-configuration-cache`.
  4. Shell loop listing `~/.m2/repository/dev/unityinflow/kore-*/0.0.1/` so
     the run log explicitly shows every published module.

Purpose (stated in the header comment): *"Safety net before pressing
`git tag v0.0.1 && git push --tags`. Run this first. If green, you're safe
to tag."*

### Artifact 4 — `docs/RELEASE-CHECKLIST.md` (commit `861d291`)

Comprehensive 400+ line operational checklist covering:

- **Pre-flight (PF-00..PF-05):** repo creation, Sonatype namespace claim
  (with both GitHub org and DNS TXT paths), runner verification (already
  done), optional Docker check, GPG key generation + keyserver upload,
  and setting the four org-level secrets via `gh secret set --org
  UnityInFlow`.
- **Dry-run verification:** how to trigger `release-dry-run.yml` from the
  UI + `gh run watch` command.
- **Real release:** `git tag v0.0.1 && git push`, watching the workflow,
  inspecting the 11-module staging bundle in the portal, and pressing the
  **Publish** button.
- **Verification:** `curl -sI` sweep across all 11 modules on
  `repo.maven.apache.org`, GPG signature verification, and a smoke-test
  Gradle project that pulls `dev.unityinflow:kore-spring:0.0.1` from
  Central.
- **Post-release:** `0.0.2-SNAPSHOT` bump + announcement targets.
- **Rollback:** explicit policy for the three time windows (before
  Publish, after Publish before replication, after replication).
- **Done-when signals:** 11-item checklist the user ticks off as they
  progress.

Every bash block is copy-pasteable — no `<LONG_KEY_ID>` style placeholders
without explicit substitution steps, no "do the obvious thing" gaps.

## Completed (Phase A autonomous scope)

| Task | Status | Commit | Artifacts |
|---|---|---|---|
| 1a — Bump root version to `0.0.1` | COMPLETE | `f81ee03` | `build.gradle.kts` |
| 1b — Create release.yml workflow | COMPLETE | `f880c80` | `.github/workflows/release.yml` |
| 1c — Create release-dry-run.yml safety net | COMPLETE | `b87d60a` | `.github/workflows/release-dry-run.yml` |
| 1d — Create RELEASE-CHECKLIST.md operational doc | COMPLETE | `861d291` | `docs/RELEASE-CHECKLIST.md` |
| 1e — Log deferred items from regression scan | COMPLETE | (this commit) | `.planning/phases/04-event-bus-publishing/deferred-items.md` |

## Awaiting Human Action (Phase B — blocks resumption)

> **The user MUST complete every item in this section before the orchestrator
> resumes plan 04-06.** See `docs/RELEASE-CHECKLIST.md` for exact commands.

### Pre-flight gates (Checkpoint 1 from the original plan)

- [ ] **PF-00** — `gh repo create UnityInFlow/kore-runtime --public --push`
      (repo does not exist on GitHub yet — recon confirmed). Once created,
      `git remote -v` should show `origin → UnityInFlow/kore-runtime`.
- [ ] **PF-01** — Claim + verify the `dev.unityinflow` namespace on Sonatype
      Central Portal (GitHub org verification is easier than DNS TXT since
      the user already owns the org).
- [x] **PF-02** — Runner group + label audit: **already verified** by
      orchestrator reconnaissance. `Default` runner group has
      `allows_public_repositories: true`; three Hetzner X64 runners online
      with `arc-runner-unityinflow` label; orangepi ARM runner correctly
      excluded from that label.
- [ ] **PF-03** — Optional Docker check on Hetzner runner. Not release-
      blocking because integration tests are `@Tag("integration")`-excluded
      from the default release build.
- [ ] **PF-04** — Generate GPG key, publish public half to
      `keyserver.ubuntu.com` + `keys.openpgp.org`.
- [ ] **PF-05** — Set the four GitHub org-level secrets via
      `gh secret set --org UnityInFlow --visibility all`: `SIGNING_KEY`,
      `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`.

### RC dry-run (Checkpoint 2 from the original plan)

- [ ] **Run release-dry-run workflow** from the GitHub Actions UI after
      PF-00..PF-05 complete. Must be green before pushing any real tag.
      Original plan 04-06 specified tagging `v0.0.1-rc1` on a feature
      branch; Phase A substitutes the simpler `release-dry-run`
      workflow_dispatch path. **This is a deliberate deviation from the
      original plan:** `workflow_dispatch` is safer and faster than RC
      tagging because it exercises the CI path without touching any git
      tags at all. If the user prefers the RC-tag approach, both options
      are documented in `docs/RELEASE-CHECKLIST.md`.

### Final release (Checkpoint 3 from the original plan)

- [ ] **Tag + push** — `git tag v0.0.1 && git push origin v0.0.1` triggers
      `release.yml`. Watch via `gh run watch --repo UnityInFlow/kore-runtime`.
- [ ] **Sonatype Publish button** — Log into <https://central.sonatype.com/>,
      inspect the 11-module `kore-0.0.1` staging bundle, press **Publish**
      (IRREVERSIBLE).
- [ ] **Maven Central resolvability** — `curl -sI` sweep after 10–30 min
      replication; GPG signature verification; Gradle smoke-test project
      resolving `dev.unityinflow:kore-spring:0.0.1`.

### Post-release (Task 3 from the original plan)

- [ ] **Bump `0.0.2-SNAPSHOT`** — Edit `build.gradle.kts` back to
      `version = "0.0.2-SNAPSHOT"`, commit as
      `chore(release): bump version to 0.0.2-SNAPSHOT post v0.0.1 release`,
      push to main.
- [ ] **Announce** — GSD Discord `#releases`, Twitter/X, r/Kotlin, r/ClaudeAI.

## Deviations from Plan

### [Rule 2 — Critical functionality] Added `release-dry-run.yml` safety workflow

- **Found during:** Phase A scope review.
- **Issue:** The original plan 04-06 Task specifies a Checkpoint 2 human
  step of tagging `v0.0.1-rc1` on a feature branch, watching the real
  `release.yml` workflow, inspecting the staging bundle in Sonatype, and
  dropping it. This is workable but couples the CI verification to real
  Sonatype credentials (PF-05 must already be complete) AND exposes the
  user to a real (if dropped) Sonatype staging deployment during the
  rehearsal.
- **Fix:** Added a parallel `release-dry-run.yml` workflow that runs via
  `workflow_dispatch`, requires no secrets, runs `publishToMavenLocal`
  instead of `publishAggregationToCentralPortal`, and verifies the build
  graph + runner toolchain end-to-end without touching Sonatype. The
  original RC-tag path remains available for users who want the full
  portal rehearsal (documented in `docs/RELEASE-CHECKLIST.md`).
- **Impact:** Strictly additive — reduces the minimum viable pre-flight
  footprint. Users who want belt-and-braces can still tag an RC.
- **Files created:** `.github/workflows/release-dry-run.yml`
- **Commit:** `b87d60a`

### [Rule 3 — Scope] No X64 label AND-pin on release.yml runs-on

- **Found during:** Phase A runner reconnaissance (performed by
  orchestrator, passed through in the execution context).
- **Issue:** The plan's original release.yml snippet shows
  `runs-on: [arc-runner-unityinflow]` without an X64 AND-pin, but the
  Wave 2 iteration-2 M3 fix from plan 04-01 (mentioned in CONTEXT.md)
  emphasized dropping X64. Needed to confirm no regression from that
  decision in the release path.
- **Fix:** Kept `runs-on: [arc-runner-unityinflow]` as a single-element
  list. Reconnaissance confirms the `arc-runner-unityinflow` label is
  only attached to the three X64 Hetzner runners (`hetzner-runner-1/2/3`);
  the orangepi ARM runner is excluded. Adding X64 as an AND-query would
  be redundant and fragile against future label renames.
- **Impact:** None — matches CLAUDE.md default and Wave 2 decision.
- **Files modified:** `.github/workflows/release.yml` (header comment
  documents the Pitfall 10 rationale).
- **Commit:** `f880c80`

## Deferred Issues

### Pre-existing `kore-observability:test` failure (blocks release dry-run)

Ran `./gradlew clean build --no-configuration-cache` after the version
bump as a regression check. The build failed with 12 failing tests in
`ObservableAgentRunnerTest` (`NoClassDefFoundError` /
`ExceptionInInitializerError` at line 76 and 88). Git-stashed the Phase A
changes and re-ran the failing task against the unmodified `main` tree
— the identical 12 failures reproduce, confirming this is **pre-existing
and unrelated to the version bump or any Phase A edit**.

Per executor scope boundary, this failure is NOT fixed in plan 04-06
Phase A. Logged in `deferred-items.md` with full reproduction steps.

**Release impact:** the `release-dry-run.yml` workflow will FAIL as long
as this regression is present, because it runs the default
`./gradlew build`. This must be fixed before the user can complete
Phase B Step 1 (dry-run green), which is a precondition for the final
tag push. A gap-closure plan (plan `04-07` or a `/gsd-debug` session)
should be scheduled BEFORE the user attempts PF-05.

**Likely root cause:** OTel SDK class-loader issue — `ObservableAgentRunner`
class initializer at line 76 probably references a class that is not on
the test runtime classpath. Investigation should start with
`kore-observability/build.gradle.kts` test deps and the OTel version
alignment with Spring Boot 4's OpenTelemetry starter bundle.

See `.planning/phases/04-event-bus-publishing/deferred-items.md` for full
details.

## Authentication Gates

None in Phase A — this phase is local-file-only. Authentication gates
(Sonatype login, GPG key handling, `gh secret set`) are all documented in
`docs/RELEASE-CHECKLIST.md` and handled by the user during PF-00..PF-05,
which the orchestrator treats as the Checkpoint 1 human-action gate.

## Pitfall Defenses Summary

| Pitfall | Defense Location | Verification |
|---|---|---|
| Pitfall 3 — Sonatype namespace unclaimed | PF-01 in RELEASE-CHECKLIST.md with both GitHub-org and DNS-TXT verification paths | User action required |
| Pitfall 7 — GPG key expired / unpublished | PF-04 in RELEASE-CHECKLIST.md with `--send-keys` to two keyservers | User action required |
| Pitfall 10 — Wrong runner architecture | Single-label `runs-on: [arc-runner-unityinflow]`, header comment explains why no X64 AND-pin | `grep -c "runs-on: \[arc-runner-unityinflow\]" .github/workflows/release.yml` returns 1 |
| Pitfall 11 — Configuration cache + env vars | `--no-configuration-cache` on both build and publish steps | `grep -c "\-\-no-configuration-cache" .github/workflows/release.yml` returns 3 (1 header comment + 2 run steps) |

## Threat Model Reference

From the plan 04-06 frontmatter threat register:

- **T-04-07** (EoP via GPG key leakage through CI logs): mitigated by
  `--no-configuration-cache` + GitHub Actions built-in log masking. Header
  comment in `release.yml` documents this.
- **T-04-09** (Repudiation of artifact provenance): mitigated by
  `fetch-depth: 0` on checkout + `generate_release_notes: true` on
  `softprops/action-gh-release@v2`.
- **T-04-10** (Compromised self-hosted runner): accepted with documented
  trust boundary. Header comment in `release.yml` states "A compromised
  runner = org compromise, which is out of scope for this plan; the human
  RC dry-run checkpoint catches anomalous staging bundles via Sonatype
  portal inspection before the manual Publish button is pressed."
- **T-04-11** (DoS via accidental re-run on same tag): mitigated naturally
  — Sonatype Central Portal rejects duplicate version uploads. Worst case
  is a red CI run; no harm done.

## Known Stubs

None. The Phase A artifacts are complete as-is. The PARTIAL status reflects
remaining HUMAN work, not missing code.

## Next Session Continuation Instructions

When the user returns after completing Phase B pre-flight:

1. **Verify human pre-flight complete** — check the boxes in the "Awaiting
   Human Action → Pre-flight gates" section above. The user should reply
   with "pre-flight approved" or a list of blockers.
2. **Fix the deferred `ObservableAgentRunnerTest` failure FIRST** — this is
   now a release-path blocker. Do not proceed to the dry-run until it is
   green. Schedule this as `gsd-debug` or a small gap-closure plan.
3. **Run `release-dry-run.yml` via `gh workflow run`** — once main is
   green again.
4. **Resume plan 04-06 Phase B** — tag push, watch release workflow,
   inspect + publish the Sonatype staging bundle, verify Maven Central,
   bump `0.0.2-SNAPSHOT`, announce.
5. **Finalize plan 04-06** — after Phase B completes, replace this partial
   SUMMARY with a complete SUMMARY documenting the full release timeline,
   update STATE.md to `completed_plans: 21`, mark ROADMAP.md Phase 4 as
   complete, and trigger the v1.0 MILESTONE COMPLETE banner from the
   original plan's `<output>` block.

## Self-Check: PASSED

- FOUND: `.github/workflows/release.yml` (commit `f880c80`)
- FOUND: `.github/workflows/release-dry-run.yml` (commit `b87d60a`)
- FOUND: `docs/RELEASE-CHECKLIST.md` (commit `861d291`)
- FOUND: `build.gradle.kts` at `version = "0.0.1"` (commit `f81ee03`)
- FOUND: `.planning/phases/04-event-bus-publishing/deferred-items.md`
- FOUND: `f81ee03` in `git log --oneline`
- FOUND: `f880c80` in `git log --oneline`
- FOUND: `b87d60a` in `git log --oneline`
- FOUND: `861d291` in `git log --oneline`
- FOUND: YAML parse valid for both workflows (`python3 -c "import yaml;
  yaml.safe_load(open('.github/workflows/release.yml'))"`)
- FOUND: `grep -c "runs-on: \[arc-runner-unityinflow\]" .github/workflows/release.yml` == 1
- FOUND: `grep -c "\-\-no-configuration-cache" .github/workflows/release.yml` == 3
- NOT RUN: `./gradlew publishToMavenLocal` green end-to-end — blocked on
  pre-existing `kore-observability:test` failure (see Deferred Issues).
  Version bump itself is verified compile-clean via stash-and-diff.
