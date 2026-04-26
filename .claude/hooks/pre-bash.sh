#!/bin/bash
COMMAND="$CLAUDE_TOOL_INPUT_COMMAND"

# Block direct Flyway migration runs
if echo "$COMMAND" | grep -qE "flyway:migrate|flywayMigrate|migrate --env"; then
  echo "ERROR: Do not run migrations directly. Ask the user to run them." >&2
  exit 1
fi

# Block production deployments
if echo "$COMMAND" | grep -qE "deploy.*(prod|production)|helm upgrade.*prod"; then
  echo "ERROR: Production deployments require explicit human approval." >&2
  exit 1
fi

# Block force-push
if echo "$COMMAND" | grep -q "git push --force\|git push -f"; then
  echo "ERROR: Force push is not allowed. Use --force-with-lease and confirm with user." >&2
  exit 1
fi

# Block dropping databases
if echo "$COMMAND" | grep -qiE "drop (database|schema)"; then
  echo "ERROR: Dropping databases requires human confirmation." >&2
  exit 1
fi
