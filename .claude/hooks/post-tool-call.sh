#!/bin/bash
cd "$CLAUDE_PROJECT_DIR" || exit 0

# Skip if no Gradle wrapper yet (project not scaffolded)
[ -f "./gradlew" ] || exit 0

# Run ktlint + compile in parallel, silent on success
OUTPUT=$(./gradlew ktlintCheck compileKotlin -q 2>&1)

if [ $? -ne 0 ]; then
  echo "Build/lint errors:" >&2
  echo "$OUTPUT" >&2
  exit 2   # exit 2 = re-engage agent to fix errors before finishing
fi

# SUCCESS: completely silent — nothing added to context
