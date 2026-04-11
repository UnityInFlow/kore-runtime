package dev.unityinflow.kore.mcp

import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.mcp.client.McpClientAdapter
import dev.unityinflow.kore.mcp.client.McpConnectionManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpClientAdapterTest {
    private lateinit var connectionManager: McpConnectionManager
    private lateinit var client: Client
    private lateinit var adapter: McpClientAdapter

    @BeforeEach
    fun setUp() {
        client = mockk()
        connectionManager = mockk()
        coEvery { connectionManager.getClient() } returns client
        adapter = McpClientAdapter(connectionManager)
    }

    @Test
    fun `listTools returns ToolDefinition list from mocked tools-list response`() =
        runTest {
            // ARRANGE
            val tool1 =
                Tool(
                    name = "file_read",
                    description = "Reads a file",
                    inputSchema = ToolSchema(),
                )
            val tool2 =
                Tool(
                    name = "file_write",
                    description = "Writes a file",
                    inputSchema = ToolSchema(),
                )
            coEvery { client.listTools() } returns ListToolsResult(tools = listOf(tool1, tool2))

            // ACT
            val tools = adapter.listTools()

            // ASSERT
            tools.size shouldBe 2
            tools[0].name shouldBe "file_read"
            tools[0].description shouldBe "Reads a file"
            tools[1].name shouldBe "file_write"
            coVerify { connectionManager.getClient() }
        }

    @Test
    fun `callTool sends correct request and returns ToolResult`() =
        runTest {
            // ARRANGE
            val requestSlot = slot<CallToolRequest>()
            coEvery { client.callTool(capture(requestSlot)) } returns
                CallToolResult(
                    content = listOf(TextContent(text = "file content here")),
                    isError = false,
                )

            val call =
                ToolCall(
                    id = "call-123",
                    name = "file_read",
                    arguments = """{"path": "/tmp/test.txt"}""",
                )

            // ACT
            val result = adapter.callTool(call)

            // ASSERT
            result.toolCallId shouldBe "call-123"
            result.content shouldContain "file content here"
            result.isError shouldBe false
            requestSlot.captured.params.name shouldBe "file_read"
        }

    @Test
    fun `callTool returns isError=true on SDK exception (T-06-02)`() =
        runTest {
            // ARRANGE
            coEvery { client.callTool(any()) } throws RuntimeException("MCP server returned malformed response")

            val call =
                ToolCall(
                    id = "call-999",
                    name = "broken_tool",
                    arguments = "{}",
                )

            // ACT
            val result = adapter.callTool(call)

            // ASSERT
            result.toolCallId shouldBe "call-999"
            result.isError shouldBe true
            result.content shouldContain "MCP tool call failed"
        }

    @Test
    fun `connection is not established until first listTools call (lazy connection D-10)`() =
        runTest {
            // ARRANGE — adapter was created but getClient() should NOT have been called yet
            // (the coEvery is set up in setUp, but we verify 0 calls before listTools)

            // The connection manager mock captures calls; at construction of McpClientAdapter
            // no call to getClient() should have happened.
            coVerify(exactly = 0) { connectionManager.getClient() }

            // Now trigger connection via listTools
            coEvery { client.listTools() } returns ListToolsResult(tools = emptyList())
            adapter.listTools()

            // ASSERT — now getClient() should have been called exactly once
            coVerify(exactly = 1) { connectionManager.getClient() }
        }

    @Test
    fun `readResource returns resource content from MCP server (MCP-03)`() =
        runTest {
            // ARRANGE
            coEvery { client.readResource(any()) } returns
                ReadResourceResult(
                    contents = listOf(TextResourceContents(text = "resource body", uri = "file:///tmp/data.txt")),
                )

            // ACT
            val content = adapter.readResource("file:///tmp/data.txt")

            // ASSERT
            content shouldNotBe null
            content shouldContain "resource body"
        }

    @Test
    fun `getPrompt returns prompt template content from MCP server (MCP-04)`() =
        runTest {
            // ARRANGE
            coEvery { client.getPrompt(any()) } returns
                GetPromptResult(
                    messages =
                        listOf(
                            PromptMessage(
                                role = Role.User,
                                content = TextContent(text = "Summarise the following:"),
                            ),
                        ),
                )

            // ACT
            val prompt = adapter.getPrompt("summarise_prompt", mapOf("topic" to "AI"))

            // ASSERT
            prompt shouldContain "Summarise the following:"
        }
}
