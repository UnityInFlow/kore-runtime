-- V2__add_parent_run_id.sql
-- HIER-04: child agent runs record their parent's run id for run-tree reconstruction.
--
-- LANDMINE (D-10) — DO NOT ADD A FOREIGN KEY HERE.
-- A child completes DURING the parent loop and INSERTs its agent_runs row BEFORE the
-- parent's row exists (the parent records its own row only after AgentLoop.run returns,
-- AgentLoop.kt:81). A self-referencing FK would reject the child insert. A plain
-- nullable column is fully queryable for run trees.
ALTER TABLE agent_runs ADD COLUMN parent_run_id UUID NULL;
CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_run_id ON agent_runs(parent_run_id);
