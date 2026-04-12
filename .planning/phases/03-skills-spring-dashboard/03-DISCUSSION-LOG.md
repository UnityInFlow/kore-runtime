# Phase 3: Skills, Spring & Dashboard - Discussion Log

> **Audit trail only.** Decisions in CONTEXT.md.

**Date:** 2026-04-12
**Phase:** 03-skills-spring-dashboard
**Areas discussed:** Skill YAML schema, Spring auto-config, Dashboard data source, Dashboard scope

---

## Skill YAML Schema

### Schema Style

| Option | Description | Selected |
|--------|-------------|----------|
| Simple + declarative (Rec.) | Pure data YAML: name, activation pattern (regex), prompt | ✓ |
| Simple + executable | YAML with optional Kotlin class reference | |
| You decide | Claude picks | |

**User's choice:** Simple + declarative

### Skill Location

| Option | Description | Selected |
|--------|-------------|----------|
| Classpath + filesystem (Rec.) | Built-in from JAR resources, user skills from filesystem directory | ✓ |
| Classpath only | JAR resources only | |
| Filesystem only | Configured directory only | |

**User's choice:** Classpath + filesystem

---

## Spring Auto-Config

### Agent Registration

| Option | Description | Selected |
|--------|-------------|----------|
| Bean-per-agent (Rec.) | @Bean functions return AgentRunner from DSL. Idiomatic Spring DI | ✓ |
| KoreAgentRegistry | Central registry configured via YAML or DSL | |
| Both: beans + registry | Support both patterns | |

**User's choice:** Bean-per-agent

### Auto-Configuration Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal (Rec.) | Auto-create beans with classpath detection. kore-storage/observability/dashboard activate when present | ✓ |
| Everything explicit | Users configure each component manually | |
| You decide | Claude picks defaults | |

**User's choice:** Minimal

---

## Dashboard Data Source

| Option | Description | Selected |
|--------|-------------|----------|
| Hybrid (Rec.) | In-memory for active agents, PostgreSQL for history | ✓ |
| PostgreSQL only | Everything from database | |
| In-memory only | Current session only | |

**User's choice:** Hybrid
**Notes:** EventBus subscription for active state, PostgreSQL query for history. HTMX polling (2s/5s/10s).

---

## Dashboard Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Single page (Rec.) | /kore with 3 HTMX fragments (active/runs/costs). No drill-down | ✓ |
| Page + detail | Overview + per-run detail page | |
| You decide | Claude picks | |

**User's choice:** Single page
**Notes:** Minimum viable monitoring. Drill-down deferred to v0.2.

---

## Claude's Discretion

- YAML parser choice
- AuditLog query method signatures
- Dashboard HTML/CSS styling
- SkillRegistry placement (kore-core port vs kore-skills only)

## Deferred Ideas

- Skill hot-reload (filesystem watcher)
- Dashboard drill-down pages
- WebSocket streaming for active agents
- Real budget-breaker integration
