# Phase 2: Observability & Storage - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-12
**Phase:** 02-observability-storage
**Areas discussed:** OTel span design, PostgreSQL schema, Exposed vs R2DBC, Metrics cardinality

---

## OTel Span Design

### Span Depth

| Option | Description | Selected |
|--------|-------------|----------|
| 3-level (Rec.) | agent run → LLM call → tool use. Covers what operators debug | ✓ |
| 2-level minimal | agent run → LLM call. Tools as span events | |
| 4-level detailed | Adds per-iteration spans | |

**User's choice:** 3-level
**Notes:** Maps to OBSV-01/02/03 requirements.

### Span Naming

| Option | Description | Selected |
|--------|-------------|----------|
| Kore-specific (Rec.) | kore.agent.run, kore.llm.call, kore.tool.use namespace | ✓ |
| GenAI semconv | OpenTelemetry GenAI conventions (experimental) | |
| You decide | Claude's discretion | |

**User's choice:** Kore-specific
**Notes:** GenAI semconv still experimental, could break.

### Context Propagation

| Option | Description | Selected |
|--------|-------------|----------|
| kore-observability module | ObservableAgentRunner wraps core runner, injects context element. Core stays clean | ✓ |
| kore-core with optional | Core accepts optional CoroutineContext, adapter fills it | |
| You decide | Claude's discretion | |

**User's choice:** kore-observability module

---

## PostgreSQL Schema

| Option | Description | Selected |
|--------|-------------|----------|
| JSONB columns (Rec.) | Typed core columns + JSONB for flexible data | ✓ |
| Fully typed | All columns typed, no JSONB | |
| You decide | Claude's discretion | |

**User's choice:** JSONB columns
**Notes:** 3 tables (agent_runs, llm_calls, tool_calls) with FK relationships. Append-only.

---

## Exposed vs R2DBC

| Option | Description | Selected |
|--------|-------------|----------|
| Exposed DSL + R2DBC | Exposed 1.0 DSL with R2DBC. Own transaction management. Avoids Spring TX conflict | ✓ |
| Spring Data R2DBC | Spring reactive repos. Simpler integration but less Kotlin-idiomatic | |
| Raw R2DBC client | No ORM. Direct SQL | |

**User's choice:** Exposed DSL + R2DBC
**Notes:** Avoids GitHub #1722 (Exposed + Spring @Transactional conflict) by using Exposed's own transactions.

---

## Metrics Cardinality

| Option | Description | Selected |
|--------|-------------|----------|
| Low cardinality (Rec.) | 4 counters + 1 gauge, 2-3 labels each. No per-tool metrics | ✓ |
| Medium cardinality | Adds per-tool metrics. Risk: many series | |
| You decide | Claude's discretion | |

**User's choice:** Low cardinality
**Notes:** Tool-level detail in OTel spans, not metrics.

---

## Claude's Discretion

- R2DBC connection pooling config
- Flyway migration naming convention
- PostgreSQL index strategy
- ObservableAgentRunner implementation pattern

## Deferred Ideas

None — discussion stayed within phase scope
