package dev.unityinflow.kore.mcp.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/** Transport types supported by kore MCP client (per D-09). */
enum class McpTransportType { STDIO, SSE }

/**
 * Configuration for a single MCP server connection.
 *
 * @param name Logical name for this server (used in error messages and logging).
 * @param transportType Transport protocol: STDIO (subprocess) or SSE (HTTP).
 * @param command For STDIO: command to execute (e.g., ["npx", "-y", "@github/github-mcp-server"]).
 * @param sseUrl For SSE: SSE endpoint URL of the MCP server.
 * @param env Additional environment variables for the subprocess (STDIO only).
 * @param maxReconnectAttempts Maximum connection attempts before giving up.
 * @param reconnectDelayMs Base delay in ms between reconnect attempts (multiplied by attempt index).
 */
data class McpServerConfig(
    val name: String,
    val transportType: McpTransportType,
    /** For STDIO: command to execute (e.g., ["npx", "-y", "@github/github-mcp-server"]). */
    val command: List<String> = emptyList(),
    /** For SSE: SSE endpoint URL (e.g., "https://mcp.example.com/sse"). */
    val sseUrl: String = "",
    val env: Map<String, String> = emptyMap(),
    val maxReconnectAttempts: Int = 3,
    val reconnectDelayMs: Long = 1_000L,
)

/**
 * Manages lazy connection and reconnection for a single MCP server.
 *
 * Connection is established on first [getClient] call — not at construction time (per D-10).
 * Auto-reconnect with exponential backoff per D-11.
 *
 * Capability negotiation (MCP initialize handshake) is handled by [Client.connect]
 * — the SDK sends InitializeRequest and receives InitializeResult before returning (per D-12).
 */
class McpConnectionManager(
    private val config: McpServerConfig,
) {
    private val mutex = Mutex()
    private var client: Client? = null

    /**
     * Get the connected [Client]. Connects lazily on first call.
     * Thread-safe via [Mutex].
     */
    suspend fun getClient(): Client =
        mutex.withLock {
            client ?: connect().also { client = it }
        }

    private suspend fun connect(): Client {
        var lastException: Throwable? = null
        repeat(config.maxReconnectAttempts) { attempt ->
            try {
                return createAndConnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastException = e
                if (attempt < config.maxReconnectAttempts - 1) {
                    delay(config.reconnectDelayMs * (attempt + 1))
                }
            }
        }
        throw lastException ?: RuntimeException("Failed to connect to MCP server '${config.name}'")
    }

    private suspend fun createAndConnect(): Client {
        val mcpClient =
            Client(
                clientInfo = Implementation(name = "kore", version = "0.0.1"),
                options = ClientOptions(capabilities = ClientCapabilities()),
            )
        // MCP capability negotiation (initialize handshake) happens inside connect().
        // The SDK sends InitializeRequest + receives InitializeResult before returning.
        // This satisfies D-12 and MCP-06.
        when (config.transportType) {
            McpTransportType.STDIO -> {
                val process =
                    ProcessBuilder(config.command)
                        .apply { environment().putAll(config.env) }
                        .start()
                // StdioClientTransport takes kotlinx.io Source/Sink — wrap Java streams
                val transport =
                    StdioClientTransport(
                        input = process.inputStream.asSource().buffered(),
                        output = process.outputStream.asSink().buffered(),
                    )
                mcpClient.connect(transport)
            }
            McpTransportType.SSE -> {
                // SseClientTransport requires an HttpClient with the SSE plugin installed.
                // Per T-06-03: SSE transport implements Last-Event-ID resumability — disconnect
                // does not cancel in-flight operations.
                val httpClient =
                    HttpClient(CIO) {
                        install(SSE)
                    }
                val transport =
                    SseClientTransport(
                        client = httpClient,
                        urlString = config.sseUrl,
                    )
                mcpClient.connect(transport)
            }
        }
        return mcpClient
    }

    /** Close the active connection and reset so the next [getClient] reconnects. */
    suspend fun close() =
        mutex.withLock {
            client?.close()
            client = null
        }
}
