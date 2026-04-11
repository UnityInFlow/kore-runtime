---
phase: 01-core-runtime
plan: "06"
subsystem: kore-mcp
tags: [kotlin, mcp, client, server, stdio, sse, tool-provider, agent-runner, tdd, coroutines, hexagonal]

# Dependency graph
requires:
  - 01-02 (ToolProvider port, ToolDefinition, ToolCall, ToolResult)
  - 01-03 (AgentRunner, AgentTask, AgentResult sealed class)
provides:
  - McpClientAdapter: ToolProvider implementation backed by MCP server (stdio + SSE transports)
  - McpConnectionManager: lazy connect + exponential-backoff reconnect for MCP servers
  - McpServerAdapter: exposes kore AgentRunners as MCP tools callable by external MCP clients
  - DSL factory functions: mcp(name, *command) and mcpSse(name, url) in McpServers.kt
  - logback-mcp.xml: routes ALL logging to stderr to prevent stdout contamination in stdio mode
affects:
  - 01-07+ (kore-skills can expose agents over MCP using McpServerAdapter)
  - Any plan using agent { tools = mcp("github", ...) } DSL

# Tech tracking
tech-stack:
  added:
    - io.modelcontextprotocol:kotlin-sdk:0.11.0 (planned 0.11.1; 0.11.0 is what resolved on Maven)
    - io.ktor:ktor-client-cio (CIO engine required for SseClientTransport HttpClient)
    - kotlinx-io (Source/Sink API used by StdioClientTransport and StdioServerTransport)
  patterns:
    - TDD RED/GREEN: test files written before production code in both tasks
    - Hexagonal: McpClientAdapter implements ToolProvider port; external MCP is an adapter detail
    - Lazy connection: McpConnectionManager.getClient() connects on first call, not construction (D-10)
    - Reconnect with exponential backoff: repeat(maxAttempts) + delay(base * attempt) (D-11)
    - SDK-managed capability negotiation: Client.connect() sends InitializeRequest internally (D-12)
    - Server-side dispatching: McpServerAdapter.invokeAgent() extracted for testability without transport

# Key files
key-files:
  created:
    - kore-mcp/src/main/kotlin/dev/unityinflow/kore/mcp/client/McpClientAdapter.kt
    - kore-mcp/src/main/kotlin/dev/unityinflow/kore/mcp/client/McpConnectionManager.kt
    - kore-mcp/src/main/kotlin/dev/unityinflow/kore/mcp/McpServers.kt
    - kore-mcp/src/main/resources/logback-mcp.xml
    - kore-mcp/src/main/kotlin/dev/unityinflow/kore/mcp/server/McpServerAdapter.kt
    - kore-mcp/src/test/kotlin/dev/unityinflow/kore/mcp/McpClientAdapterTest.kt
    - kore-mcp/src/test/kotlin/dev/unityinflow/kore/mcp/McpServerAdapterTest.kt

# Decisions
decisions:
  - SDK version resolved as 0.11.0 (plan specified 0.11.1; 0.11.0 is the available version)
  - McpServerAdapter.invokeAgent() made public suspend fun — extracted from MCP tool handler lambda for testability without a real transport (no test touches startStdio)
  - MutableMap inside McpServerAdapter uses LinkedHashMap (val, mutated in place) — registration order is preserved for deterministic tools/list responses
  - addTool lambda in SDK 0.11.0 takes (ClientConnection, CallToolRequest) -> CallToolResult (2 params), not 3 as shown in plan pseudo-code
  - CallToolRequest.arguments accessed via .getArguments() convenience property (not .params.arguments)
  - StdioServerTransport constructor uses inputStream/outputStream param names (same Source/Sink types as client but different names)

# Metrics
metrics:
  duration: "~10 minutes"
  completed: "2026-04-10"
  tasks: 2
  files: 7
---

# Phase 01 Plan 06: kore-mcp (MCP Client + Server) Summary

**One-liner:** MCP client adapter implementing ToolProvider (stdio + SSE, lazy connect, reconnect) and MCP server adapter exposing kore agents as callable MCP tools.

## What Was Built

### Task 1 (committed 1244d2b — already complete at resume)

McpClientAdapter + McpConnectionManager + McpServers.kt + logback-mcp.xml + McpClientAdapterTest.kt

**McpClientAdapter API:**
- `McpClientAdapter(connectionManager: McpConnectionManager)` — constructor
- `suspend fun listTools(): List<ToolDefinition>` — triggers lazy connection on first call
- `suspend fun callTool(call: ToolCall): ToolResult` — never throws; isError=true on any SDK exception (T-06-02)
- `suspend fun readResource(uri: String): String` — reads MCP resources by URI (MCP-03)
- `suspend fun getPrompt(name: String, arguments: Map<String, String>): String` — fetches prompt templates (MCP-04)

**McpConnectionManager API:**
- `McpConnectionManager(config: McpServerConfig)` — constructor
- `suspend fun getClient(): Client` — lazy connect with mutex; exponential-backoff reconnect (D-10, D-11)
- `suspend fun close()` — closes connection and resets for next reconnect

**DSL factory functions (McpServers.kt):**
- `fun mcp(name: String, vararg command: String): McpClientAdapter` — stdio transport
- `fun mcpSse(name: String, url: String): McpClientAdapter` — SSE/HTTP transport

**logback-mcp.xml:** Routes ALL logging to `System.err`. Activate with `-Dlogback.configurationFile=logback-mcp.xml` before starting a stdio server. Prevents JSON-RPC corruption on stdout (Pitfall 4, T-06-01).

### Task 2 (committed 0b63ec0)

McpServerAdapter.kt + McpServerAdapterTest.kt

**McpServerAdapter API:**
- `McpServerAdapter(serverName: String = "kore", serverVersion: String = "0.0.1")` — constructor
- `fun registerAgent(toolName: String, runner: AgentRunner, description: String)` — registers agent as MCP tool
- `fun registeredAgentNames(): List<String>` — introspection; used in tests
- `suspend fun invokeAgent(toolName: String, inputJson: String): CallToolResult` — public for testability
- `suspend fun startStdio()` — starts stdio transport and suspends until client disconnects

**AgentResult → CallToolResult mapping:**
- `AgentResult.Success` → `isError = false`, content = output string
- `AgentResult.BudgetExceeded` → `isError = true`, "Budget exceeded: N tokens used, limit M"
- `AgentResult.ToolError` → `isError = true`, "Tool error in 'toolName': message"
- `AgentResult.LLMError` → `isError = true`, "LLM error in 'backend': message"
- `AgentResult.Cancelled` → `isError = true`, "Agent cancelled: reason"

## Test Coverage

| Test class | Tests | Status |
|---|---|---|
| McpClientAdapterTest | 6 | All pass |
| McpServerAdapterTest | 6 | All pass |
| Total | 12 | All pass |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] SDK version resolved as 0.11.0 (plan specified 0.11.1)**
- **Found during:** Task 1 (already resolved prior to this session's resume)
- **Fix:** Build uses 0.11.0 — the actual available version. APIs are identical.

**2. [Rule 1 - Bug] StdioServerTransport constructor params are `inputStream`/`outputStream`, not `input`/`output`**
- **Found during:** Task 2, first compile attempt
- **Issue:** Plan pseudo-code used `input = ...`, `output = ...` parameter names
- **Fix:** Used correct names `inputStream = ...`, `outputStream = ...` (consistent with javap inspection)
- **Files modified:** McpServerAdapter.kt

**3. [Rule 1 - Bug] Server.addTool(Tool, handler) lambda takes 2 params `(ClientConnection, CallToolRequest)`, not 3**
- **Found during:** Task 2, first compile attempt
- **Issue:** Plan pseudo-code showed a 3-param lambda including Continuation
- **Fix:** Used `server.addTool(tool) { request -> ... }` with single request param (ClientConnection implicit)
- **Files modified:** McpServerAdapter.kt

**4. [Rule 1 - Bug] CallToolRequest access via `.arguments` not `.params.arguments`**
- **Found during:** Task 2, first compile attempt
- **Fix:** Used `request.arguments` (convenience property on CallToolRequest)
- **Files modified:** McpServerAdapter.kt

**5. [Rule 2 - Missing functionality] McpServerAdapter.invokeAgent() extracted as public method**
- **Found during:** Task 2 design
- **Issue:** Plan had tool dispatch only inside MCP server lambda — untestable without a real transport
- **Fix:** Extracted `invokeAgent(toolName, inputJson)` as `suspend fun` callable from tests directly
- **Files modified:** McpServerAdapter.kt

## Threat Model Coverage

| Threat ID | Status |
|-----------|--------|
| T-06-01 (stdout contamination) | Mitigated — logback-mcp.xml in resources, documented activation |
| T-06-02 (malformed MCP response) | Mitigated — callTool() wraps all exceptions in isError=true ToolResult |
| T-06-03 (SSE disconnect) | Mitigated — SseClientTransport from SDK handles Last-Event-ID resumability |
| T-06-04 (arbitrary agent input) | Mitigated — input passed as AgentTask.input string, not executed as code |
| T-06-05 (result lost on disconnect) | Mitigated — SSE resumability buffer (SDK feature) |
| T-06-06 (info disclosure) | Accepted — single-user Phase 1 |

## Known Stubs

None — all MCP client and server functionality is wired to real SDK types.

## Threat Flags

None — no new network endpoints or trust boundaries beyond what the plan's threat model covers.

## Self-Check: PASSED

All 7 created files found on disk. Both commits (1244d2b, 0b63ec0) confirmed in git log.
