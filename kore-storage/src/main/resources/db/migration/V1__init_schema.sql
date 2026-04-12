-- kore audit log schema
-- Append-only: no UPDATE or DELETE operations in application code (D-16)

CREATE TABLE IF NOT EXISTS agent_runs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_name  TEXT        NOT NULL,
    task        TEXT        NOT NULL,
    result_type TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    metadata    JSONB       NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS llm_calls (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      UUID        NOT NULL REFERENCES agent_runs(id),
    model       TEXT        NOT NULL,
    tokens_in   INTEGER     NOT NULL DEFAULT 0,
    tokens_out  INTEGER     NOT NULL DEFAULT 0,
    duration_ms INTEGER     NOT NULL DEFAULT 0,
    metadata    JSONB       NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS tool_calls (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      UUID        NOT NULL REFERENCES agent_runs(id),
    llm_call_id UUID        REFERENCES llm_calls(id),
    tool_name   TEXT        NOT NULL,
    mcp_server  TEXT,
    duration_ms INTEGER     NOT NULL DEFAULT 0,
    arguments   JSONB       NOT NULL DEFAULT '{}',
    result      JSONB       NOT NULL DEFAULT '{}'
);

-- Indexes for common audit queries (performance: filter by agent, join runs->calls)
CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_name ON agent_runs(agent_name);
CREATE INDEX IF NOT EXISTS idx_llm_calls_run_id ON llm_calls(run_id);
CREATE INDEX IF NOT EXISTS idx_tool_calls_run_id ON tool_calls(run_id);
