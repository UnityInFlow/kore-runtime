# kore-runtime v0.0.1 Release Checklist

**Status:** Not started
**Last updated:** 2026-04-15
**Prerequisites:** Phase 4 Waves 1–4 complete (15/17 tasks green). Wave 5 Phase A
(release workflow + version bump + this checklist) landed on `main`.

This checklist walks through every manual step required to ship
`dev.unityinflow:kore-*:0.0.1` to Maven Central. The release workflow
(`.github/workflows/release.yml`) and its safety-net dry-run counterpart
(`.github/workflows/release-dry-run.yml`) are already committed; what
follows is the human work.

---

## Pre-flight (one-time setup — reused by every subsequent release)

### PF-00 — Create the GitHub repo and push

The `kore-runtime` repo does not yet exist on `github.com/UnityInFlow`. Create
it and push `main` + all history:

```bash
cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime
gh repo create UnityInFlow/kore-runtime \
  --public \
  --source=. \
  --remote=origin \
  --push \
  --description "Production-grade Kotlin agent runtime for the JVM"
```

**After creation:**

```bash
git remote -v
# Expect: origin  https://github.com/UnityInFlow/kore-runtime.git (fetch/push)

gh repo view UnityInFlow/kore-runtime --json visibility,defaultBranchRef
# Expect: visibility=PUBLIC, defaultBranchRef.name=main
```

### PF-01 — Claim and verify the `dev.unityinflow` Sonatype namespace

1. Log in at <https://central.sonatype.com/> (create an account if needed — use
   the email you want to own the namespace).
2. Navigate to **Namespaces** → **Add Namespace**.
3. Enter `dev.unityinflow`.
4. Choose a verification method:
   - **Recommended:** GitHub org verification. The portal generates an
     `OSSRH-<ticket>` repo name. Create a public repo with that exact name in
     the `UnityInFlow` org:
     ```bash
     gh repo create UnityInFlow/OSSRH-<ticket-number> --public
     ```
     Sonatype polls every few minutes; the namespace flips to `VERIFIED`
     within ~30 minutes.
   - **Alternative:** DNS TXT record on `unityinflow.dev`. Only use this path
     if you actually own the `unityinflow.dev` domain.
5. Wait until the portal shows the namespace status as **VERIFIED** before
   proceeding. A not-yet-verified namespace is the #1 first-publish failure
   mode (Pitfall 3 in `04-RESEARCH.md`).

### PF-02 — Runner visibility audit (already verified)

Phase A reconnaissance confirmed:

- The org runner group `Default` has `allows_public_repositories: true` — no
  action needed.
- Three Hetzner runners (`hetzner-runner-1/2/3`) are online and carry the
  label set `[arc-runner-unityinflow, self-hosted, Linux, X64]`.
- `orangepi-runner` is ARM64 and does **NOT** carry `arc-runner-unityinflow`
  — exactly what we want (Pitfall 10 defense).

No action required here. To re-verify before pressing the release button:

```bash
gh api /orgs/UnityInFlow/actions/runners \
  --jq '.runners[] | {name, status, labels: [.labels[].name]}'
```

### PF-03 — Docker on the runner (optional, non-blocking)

Integration tests are tagged `@Tag("integration")` and excluded from the
default `./gradlew test` run, so the release workflow does **not** depend on
Docker. If you later want Testcontainers-backed integration tests to run in
CI, verify Docker on one of the Hetzner runners:

```bash
# SSH into hetzner-runner-1 (or trigger an ad-hoc workflow):
docker --version
docker ps
```

If Docker is absent, skip for v0.0.1 — the release path is unaffected.

### PF-04 — Generate a GPG key and publish the public half

Sonatype requires a valid GPG signature on every artifact. Generate a key
locally, publish the public half to keyservers, and keep the private half as
the `SIGNING_KEY` org secret.

```bash
# Interactive key generation. Answer the prompts:
#   Kind: (1) RSA and RSA
#   Key size: 4096
#   Valid for: 2y  (or 0 for "never expires")
#   Real name: Jiří Hermann
#   Email: jiri@unityinflow.dev   (or your Sonatype account email)
#   Passphrase: choose one and remember it — OR empty for no passphrase
gpg --full-generate-key

# Find the long key ID:
gpg --list-secret-keys --keyid-format=long

# Export the KEY_ID (replace <LONG_KEY_ID> with your value):
KEY_ID=<LONG_KEY_ID>

# Publish the public half to multiple keyservers (Sonatype polls these):
gpg --keyserver keyserver.ubuntu.com --send-keys "$KEY_ID"
gpg --keyserver keys.openpgp.org       --send-keys "$KEY_ID"

# Export the ASCII-armored PRIVATE key — this is what goes into SIGNING_KEY.
gpg --export-secret-keys --armor "$KEY_ID" > /tmp/signing-key.asc
```

Propagation across keyservers takes a few minutes. Verify with:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys "$KEY_ID"
```

### PF-05 — Create the four GitHub org-level secrets

No org-level secrets exist yet. Create all four at the **organization** level
(not per-repo) so any UnityInFlow repo that adds a release workflow can reuse
them.

**`SONATYPE_USERNAME` / `SONATYPE_PASSWORD` are PORTAL USER TOKENS**, not your
account username/password. Generate them at
<https://central.sonatype.com/> → **Profile** → **User Token** → **Generate**.
The portal shows a `<username, password>` pair exactly once; copy both
immediately.

```bash
# 1. SIGNING_KEY — ASCII-armored GPG private key exported above.
gh secret set SIGNING_KEY \
  --org UnityInFlow \
  --visibility all \
  < /tmp/signing-key.asc

# 2. SIGNING_PASSWORD — the GPG passphrase (or an empty line if no passphrase).
gh secret set SIGNING_PASSWORD \
  --org UnityInFlow \
  --visibility all

# 3. SONATYPE_USERNAME — portal user token username.
gh secret set SONATYPE_USERNAME \
  --org UnityInFlow \
  --visibility all

# 4. SONATYPE_PASSWORD — portal user token password.
gh secret set SONATYPE_PASSWORD \
  --org UnityInFlow \
  --visibility all

# Shred the exported key file once the secret is set.
shred -u /tmp/signing-key.asc
```

**Verify:**

```bash
gh api /orgs/UnityInFlow/actions/secrets --jq '.secrets[] | .name'
# Expect (in some order):
#   SIGNING_KEY
#   SIGNING_PASSWORD
#   SONATYPE_USERNAME
#   SONATYPE_PASSWORD
```

---

## Dry-run verification (before touching real tags)

### Step 1 — Trigger the `release-dry-run` workflow

After PF-00..PF-05 complete:

1. Visit
   `https://github.com/UnityInFlow/kore-runtime/actions/workflows/release-dry-run.yml`.
2. Click **Run workflow** → branch `main` → **Run workflow**.
3. Watch it run on a Hetzner runner:
   ```bash
   gh run watch --repo UnityInFlow/kore-runtime \
     $(gh run list --repo UnityInFlow/kore-runtime \
         --workflow=release-dry-run.yml --limit 1 \
         --json databaseId -q '.[0].databaseId')
   ```
4. The workflow runs `./gradlew clean build publishToMavenLocal` and lists
   the 11 `kore-*/0.0.1/` directories under
   `~/.m2/repository/dev/unityinflow/`.

**If dry-run is green:** you have proven the workflow plumbing + runner
toolchain + build graph all work. Proceed to Step 2.

**If dry-run fails:** fix the build or runner setup first. Do **not** push a
release tag until this workflow passes.

---

## Real release (after all pre-flight + dry-run green)

### Step 2 — Tag `v0.0.1`

```bash
cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime
git switch main
git pull --ff-only
git status   # expect: "nothing to commit, working tree clean"

git tag v0.0.1
git push origin v0.0.1
```

Pushing the tag triggers `.github/workflows/release.yml`, which will:

1. Check out the tag commit.
2. Run `./gradlew clean build --no-configuration-cache` (full unit test
   suite across all 11 modules).
3. Run `./gradlew publishAggregationToCentralPortal --no-configuration-cache`
   with the four secrets injected as env vars. This uploads a **staging
   bundle** to the Sonatype Central Portal — nothing is released to Maven
   Central yet because `publishingType = "USER_MANAGED"` (see plan 04-05).
4. Create a GitHub Release at `/releases/tag/v0.0.1` with auto-generated
   notes covering every commit since the repo started.

Watch the run:

```bash
gh run watch --repo UnityInFlow/kore-runtime \
  $(gh run list --repo UnityInFlow/kore-runtime \
      --workflow=release.yml --limit 1 \
      --json databaseId -q '.[0].databaseId')
```

### Step 3 — Inspect and publish the staging bundle in Sonatype Central Portal

1. Log in at <https://central.sonatype.com/>.
2. Navigate to **Publishing** → **Deployments**.
3. Find the deployment named `kore-0.0.1` in state `VALIDATED` (or `PENDING`).
4. Inspect the bundle contents — verify every module is present:

   - `kore-core`
   - `kore-llm`
   - `kore-mcp`
   - `kore-observability`
   - `kore-storage`
   - `kore-skills`
   - `kore-spring`
   - `kore-dashboard`
   - `kore-test`
   - `kore-kafka`
   - `kore-rabbitmq`

   For each module, confirm:
   - `jar`, `sources.jar`, `javadoc.jar`, `pom`
   - `.asc` signature files for each of the above
   - POM contains `name`, `description`, `url`, `licenses`, `developers`, `scm`

5. If anything is off, click **Drop** — the bundle is deleted without ever
   reaching Maven Central. Fix the problem, bump the patch version, and
   re-tag.
6. If everything looks correct, click **Publish** to promote the bundle to
   Maven Central. **This is irreversible.**

### Step 4 — Verify final Maven Central resolvability

Wait 10–30 minutes for Central replication, then:

```bash
# Spot check the flagship module first:
curl -sI https://repo.maven.apache.org/maven2/dev/unityinflow/kore-spring/0.0.1/kore-spring-0.0.1.pom
# Expect: HTTP/1.1 200 OK (or HTTP/2 200)

# Full 11-module sweep:
for m in kore-core kore-llm kore-mcp kore-observability kore-storage \
         kore-skills kore-spring kore-dashboard kore-test \
         kore-kafka kore-rabbitmq; do
  status=$(curl -sI "https://repo.maven.apache.org/maven2/dev/unityinflow/$m/0.0.1/$m-0.0.1.pom" | head -1)
  echo "$m: $status"
done
```

Verify a GPG signature end-to-end:

```bash
curl -s https://repo.maven.apache.org/maven2/dev/unityinflow/kore-spring/0.0.1/kore-spring-0.0.1.jar     -o /tmp/kore-spring-0.0.1.jar
curl -s https://repo.maven.apache.org/maven2/dev/unityinflow/kore-spring/0.0.1/kore-spring-0.0.1.jar.asc -o /tmp/kore-spring-0.0.1.jar.asc
gpg --verify /tmp/kore-spring-0.0.1.jar.asc /tmp/kore-spring-0.0.1.jar
# Expect: "Good signature from ..."
```

Smoke-test resolution from a brand-new Gradle project:

```bash
mkdir /tmp/kore-smoketest && cd /tmp/kore-smoketest

cat > settings.gradle.kts <<'EOF'
rootProject.name = "kore-smoketest"
EOF

cat > build.gradle.kts <<'EOF'
plugins { id("org.jetbrains.kotlin.jvm") version "2.3.0" }
repositories { mavenCentral() }
dependencies { implementation("dev.unityinflow:kore-spring:0.0.1") }
EOF

gradle dependencies --configuration runtimeClasspath | grep "dev.unityinflow:kore-"
# Expect: kore-spring and all transitive kore-* modules resolved from Central
```

### Step 5 — Post-release version bump on `main`

After Central replication confirms, bump the version on `main` so the next
commit is not accidentally a re-release:

```bash
cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime
git switch main
git pull --ff-only

# Edit build.gradle.kts by hand OR:
sed -i '' 's/version = "0.0.1"/version = "0.0.2-SNAPSHOT"/' build.gradle.kts

git add build.gradle.kts
git commit -m "chore(release): bump version to 0.0.2-SNAPSHOT post v0.0.1 release"
git push origin main
```

### Step 6 — Announcement

Per the CLAUDE.md release playbook, post the release in:

- **GSD Discord** — `#releases` channel
- **Twitter / X** — link to Maven Central coordinates + GitHub Release
- **r/Kotlin** — "kore-runtime v0.0.1 — Kotlin agent runtime for the JVM" with
  installation snippet
- **r/ClaudeAI** — context-setting post about the UnityInFlow ecosystem

Installation snippet for announcements:

```kotlin
dependencies {
    implementation("dev.unityinflow:kore-spring:0.0.1")
}
```

---

## Rollback

1. **Before you press `Publish` in the Sonatype portal:** click **Drop**. The
   staging bundle is deleted. No public release happened.
2. **After `Publish` but before Maven Central replication completes:** the
   release cannot be undone. Maven Central does not support deletes.
3. **After Central replication:** ship a `0.0.2` with the fix. Never reuse
   `0.0.1`. Document the regression in the GitHub Release notes for `0.0.2`.

---

## Notes

- Sonatype namespace verification can take anywhere from 10 minutes
  (GitHub method) to several hours (DNS TXT) on the first attempt.
- GPG public keys propagate across keyservers within a few minutes but
  occasionally take longer to reach all mirrors.
- The release workflow disables Gradle configuration cache on the publish
  step (Pitfall 11). Do not "optimise" this away.
- The 11-module aggregation bundle is atomic — `publishAggregationToCentralPortal`
  uploads all modules as a single Sonatype deployment. A partial upload is
  not possible.

---

## Done-when signals

- [ ] PF-00 — `kore-runtime` repo exists on GitHub with all commits pushed
- [ ] PF-01 — `dev.unityinflow` namespace VERIFIED on Sonatype Central Portal
- [ ] PF-04 — GPG key generated, private half exported, public half on
      keyservers
- [ ] PF-05 — All four org secrets set (`SIGNING_KEY`, `SIGNING_PASSWORD`,
      `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`)
- [ ] `release-dry-run` workflow green
- [ ] `v0.0.1` tag pushed
- [ ] `release` workflow green
- [ ] Staging bundle inspected and **Publish**ed in the portal UI
- [ ] All 11 modules return HTTP 200 from `repo.maven.apache.org`
- [ ] GPG signature verification succeeds on at least one module
- [ ] Smoke-test Gradle project resolves `dev.unityinflow:kore-spring:0.0.1`
- [ ] Post-release `0.0.2-SNAPSHOT` version bump committed on `main`
- [ ] Release announced in Discord + Twitter + r/Kotlin + r/ClaudeAI
