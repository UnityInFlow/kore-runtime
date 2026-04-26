#!/bin/bash
cd "$CLAUDE_PROJECT_DIR" || exit 0

# Skip if no Gradle wrapper yet (project not scaffolded)
[ -f "./gradlew" ] || exit 0

# 1. Run fast unit tests silently — surface errors only
TEST_OUTPUT=$(./gradlew test -q 2>&1)
if [ $? -ne 0 ]; then
  echo "Unit tests failed:" >&2
  echo "$TEST_OUTPUT" >&2
  exit 2
fi

# 2. Coverage check — re-engage agent if it drops
COVERAGE=$(./gradlew jacocoTestCoverageVerification -q 2>&1)
if [ $? -ne 0 ]; then
  echo "Coverage dropped below threshold. Increase test coverage before finishing." >&2
  exit 2
fi

# 3. Verify GH issue is linked on feature branches
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
if [[ "$BRANCH" == feature/* ]] || [[ "$BRANCH" == feat/* ]]; then
  ISSUE_LINKED=$(git log origin/main..HEAD --format="%s %b" 2>/dev/null | grep -cE "#[0-9]+")
  if [ "$ISSUE_LINKED" -eq 0 ]; then
    echo "No GitHub issue linked in commit history. Add before finishing." >&2
    exit 2
  fi

  # Create draft PR if it doesn't exist
  gh pr create --fill --draft 2>/dev/null || true
fi
