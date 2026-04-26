# PR Artifacts Skill
You are preparing a pull request for the kore-runtime Kotlin/Spring WebFlux codebase.
Produce ALL of the following before calling /gsd:ship or opening a PR for review.

## 1. GitHub Issue
- Verify the PR is linked to a GH issue: `gh issue view <N>` or create one:
  `gh issue create --title "<summary>" --body "..."`
- Set the issue reference in the PR body: "Closes #N"

## 2. ADR (Architecture Decision Record)
Required when the PR introduces or changes:
- A new framework, library, or runtime dependency
- An infrastructure or deployment topology change
- A security or authentication pattern
- A data model or schema decision with long-term impact
- A cross-module contract (MCP protocol, LLM backend interface, event schema)

Not required for: bug fixes, refactors within existing patterns, test additions.

Use the template at `adr_template.md`. File as `docs/adr/NNNN-short-title.md`.
NNNN = next sequential number. Commit with message: `docs(adr): NNNN short title`

## 3. Documentation Update
Update whichever of the following applies:
- `docs/` — API docs, runbooks, architecture diagrams
- `README.md` — setup instructions, environment variables
- KDoc on all public APIs — if public interface changed
- `CHANGELOG.md` — one-line entry under Unreleased: `- <summary>`

## 4. Tests
Verify the following before shipping:
- [ ] Unit tests for every new public function (`*Test.kt` in same package)
- [ ] Integration test for any new endpoint or MCP handler
- [ ] Test names follow: `should <expected behaviour> when <condition>`
- [ ] `./gradlew test` passes silently
- [ ] `./gradlew jacocoTestCoverageVerification` passes (80% minimum)

## 5. Verification Report
Produce a brief checklist in the PR description:
- [ ] All unit tests pass
- [ ] Coverage >= 80%
- [ ] ktlint clean (no new violations)
- [ ] ADR written (or explicitly not required — state why)
- [ ] Docs updated
- [ ] GH issue linked
- [ ] Manual smoke test: describe what you tested and the outcome

## PR Description Template
```
## Summary
[1-2 sentences: what changed and why]

## Changes
- [bullet: concrete change]
- [bullet: concrete change]

## Verification
- [ ] Unit tests pass
- [ ] Coverage >= 80%
- [ ] ktlint clean
- [ ] ADR: docs/adr/NNNN-title.md (or: not required because ...)
- [ ] Docs updated: [which files]
- [ ] Issue: Closes #N

## Manual Testing
[What you ran and what you observed]
```
