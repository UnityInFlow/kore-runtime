#!/bin/sh
# verify-published.sh — post-publish verification harness (KORE-06).
#
# Proves "published" in the strict sense: a clean consumer can resolve the curated kore coordinates
# AND the kore-bom from Maven Central (NOT mavenLocal, NOT a sibling project) and boot the
# auto-configuration.
#
# What it does:
#   1. Bounded-polls repo1.maven.org for the POMs of EVERY curated coordinate (the 9 stable modules)
#      AND the kore-bom platform, because KORE-06 requires the whole curated set + BOM to be
#      resolvable from Central. The poll absorbs the ~30-minute Central → repo1 propagation delay
#      AFTER the human presses Publish (RESEARCH Pitfall 4): verifying too early is the failure mode
#      this guards against.
#   2. Once all POMs are present, runs the standalone clean-consumer test, resolving kore-spring /
#      kore-core (versions aligned by the kore-bom platform import) from Central via -PkoreVersion.
#
# NOTE on `sleep`: the shell `sleep` below is a propagation poll/retry — it is the idiomatic
# choice for waiting on an external CDN. It is DISTINCT from (and not subject to) the CLAUDE.md
# ban on `Thread.sleep()` / raw threads in Kotlin async code.
#
# Usage: sh scripts/verify-published.sh [version]   (version defaults to 0.1.0)
set -eu

VER="${1:-0.1.0}"
BASE="https://repo1.maven.org/maven2"
GROUP_PATH="io/github/unityinflow"

# Bounded poll: 30 attempts x 60s = up to 30 minutes per coordinate (NOT an infinite loop).
MAX_ATTEMPTS=30
SLEEP_SECONDS=60

# Poll a single canonical repo1 POM URL until it resolves, or fail after MAX_ATTEMPTS.
# $1 = human label, $2 = full POM URL
poll_pom() {
    label="$1"
    url="$2"
    attempt=0
    while [ "$attempt" -lt "$MAX_ATTEMPTS" ]; do
        attempt=$((attempt + 1))
        if curl -fsI "$url" >/dev/null 2>&1; then
            echo "[$label] resolvable on repo1 (attempt $attempt/$MAX_ATTEMPTS): $url"
            return 0
        fi
        echo "[$label] not yet on repo1 (attempt $attempt/$MAX_ATTEMPTS) — sleeping ${SLEEP_SECONDS}s"
        sleep "$SLEEP_SECONDS"
    done
    echo "[$label] POM never appeared on repo1 after $MAX_ATTEMPTS attempts: $url" >&2
    return 1
}

echo "Verifying kore $VER (curated 9 modules + kore-bom) is resolvable from Maven Central (repo1)..."

# Curated coordinate set: the 9 stable modules + the kore-bom platform. Every one must be resolvable
# on repo1 before the clean-consumer resolve runs (KORE-06).
for art in kore-core kore-llm kore-mcp kore-observability kore-storage \
           kore-skills kore-spring kore-test kore-budget kore-bom; do
    poll_pom "$art" "${BASE}/${GROUP_PATH}/${art}/${VER}/${art}-${VER}.pom"
done

echo "All curated coordinates + kore-bom resolvable on repo1 — running clean-consumer boot test."

# Resolve + boot from Central. --refresh-dependencies so a prior 404 isn't served from cache;
# --no-configuration-cache per CLAUDE.md (signing/kapt config-cache friction). The clean-consumer
# is a standalone build with no own wrapper — it is driven via the parent kore wrapper.
./gradlew -p verification/clean-consumer --refresh-dependencies \
    -PkoreVersion="$VER" test --no-configuration-cache

echo "VERIFIED: kore $VER (curated set + kore-bom) is clean-consumer-resolvable from Maven Central."
