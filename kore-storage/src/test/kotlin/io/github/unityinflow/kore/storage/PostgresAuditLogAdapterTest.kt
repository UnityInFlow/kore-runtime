package io.github.unityinflow.kore.storage

import io.github.unityinflow.kore.core.AgentResult
import io.github.unityinflow.kore.core.AgentTask
import io.github.unityinflow.kore.core.TokenUsage
import io.github.unityinflow.kore.core.ToolCall
import io.github.unityinflow.kore.core.ToolResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

@Tag("integration")
@Testcontainers
class PostgresAuditLogAdapterTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("kore_test")
                withUsername("kore")
                withPassword("kore")
                waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
                withStartupTimeout(Duration.ofMinutes(3))
            }

        private lateinit var adapter: PostgresAuditLogAdapter

        @JvmStatic
        @BeforeAll
        fun setup() {
            val config =
                StorageConfig(
                    r2dbcUrl = "r2dbc:postgresql://kore:kore@${postgres.host}:${postgres.firstMappedPort}/kore_test",
                    jdbcUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                )
            config.migrate()
            adapter = PostgresAuditLogAdapter(config.database)
        }
    }

    @Test
    fun `recordAgentRun inserts a row readable via JDBC`() =
        runTest {
            val agentId = UUID.randomUUID().toString()
            val task = AgentTask(id = UUID.randomUUID().toString(), input = "test task input")
            val result = AgentResult.Success(output = "done", tokenUsage = TokenUsage(10, 5))

            adapter.recordAgentRun(agentId, task, result)

            postgres.createConnection("").use { conn ->
                val ps = conn.prepareStatement("SELECT count(*) FROM agent_runs WHERE id = ?::uuid")
                ps.setString(1, agentId)
                val rs = ps.executeQuery()
                rs.next() shouldBe true
                rs.getInt(1) shouldBe 1
            }
        }

    @Test
    fun `recordLLMCall inserts row with correct run_id FK`() =
        runTest {
            val agentId = UUID.randomUUID().toString()
            val task = AgentTask(id = UUID.randomUUID().toString(), input = "llm fk test")
            adapter.recordAgentRun(agentId, task, AgentResult.Success("ok", TokenUsage(1, 1)))

            adapter.recordLLMCall(agentId, "claude", TokenUsage(10, 5))

            postgres.createConnection("").use { conn ->
                val ps = conn.prepareStatement("SELECT run_id::text FROM llm_calls WHERE run_id = ?::uuid")
                ps.setString(1, agentId)
                val rs = ps.executeQuery()
                rs.next() shouldBe true
                rs.getString("run_id") shouldBe agentId
            }
        }

    @Test
    fun `recordAgentRun twice produces two rows — append-only, no overwrite`() =
        runTest {
            val agentId1 = UUID.randomUUID().toString()
            val agentId2 = UUID.randomUUID().toString()
            val task = AgentTask(id = UUID.randomUUID().toString(), input = "append test")

            adapter.recordAgentRun(agentId1, task, AgentResult.Success("a", TokenUsage(1, 1)))
            adapter.recordAgentRun(agentId2, task, AgentResult.Success("b", TokenUsage(2, 2)))

            postgres.createConnection("").use { conn ->
                val ps =
                    conn.prepareStatement(
                        "SELECT count(*) FROM agent_runs WHERE id IN (?::uuid, ?::uuid)",
                    )
                ps.setString(1, agentId1)
                ps.setString(2, agentId2)
                val rs = ps.executeQuery()
                rs.next() shouldBe true
                rs.getInt(1) shouldBe 2
            }
        }

    @Test
    fun `child run persists parent_run_id equal to parent id (HIER-04 criterion 4)`() =
        runTest {
            val parentId = UUID.randomUUID().toString()
            val parentTask = AgentTask(id = UUID.randomUUID().toString(), input = "parent work")
            adapter.recordAgentRun(parentId, parentTask, AgentResult.Success("parent done", TokenUsage(1, 1)))

            val childId = UUID.randomUUID().toString()
            val childTask =
                AgentTask(
                    id = UUID.randomUUID().toString(),
                    input = "child work",
                    parentRunId = parentId,
                )
            adapter.recordAgentRun(childId, childTask, AgentResult.Success("child done", TokenUsage(2, 2)))

            postgres.createConnection("").use { conn ->
                val ps = conn.prepareStatement("SELECT parent_run_id::text FROM agent_runs WHERE id = ?::uuid")
                ps.setString(1, childId)
                val rs = ps.executeQuery()
                rs.next() shouldBe true
                rs.getString("parent_run_id") shouldBe parentId
            }
        }

    @Test
    fun `root run leaves parent_run_id NULL`() =
        runTest {
            val rootId = UUID.randomUUID().toString()
            val rootTask = AgentTask(id = UUID.randomUUID().toString(), input = "root work")
            adapter.recordAgentRun(rootId, rootTask, AgentResult.Success("root done", TokenUsage(1, 1)))

            postgres.createConnection("").use { conn ->
                val ps = conn.prepareStatement("SELECT parent_run_id FROM agent_runs WHERE id = ?::uuid")
                ps.setString(1, rootId)
                val rs = ps.executeQuery()
                rs.next() shouldBe true
                rs.getString("parent_run_id") shouldBe null
                rs.wasNull() shouldBe true
            }
        }

    @Test
    fun `recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child correlation (CR-02)`() =
        runTest {
            // Independently mirror PostgresAuditLogAdapter's private String.toStableUuid():
            // a valid UUID string parses verbatim, any other string maps deterministically via
            // UUID.nameUUIDFromBytes. Recomputed here (not imported) so the test proves the
            // contract independently of the adapter's internals.
            val toStableUuid: String.() -> UUID = {
                runCatching { UUID.fromString(this) }
                    .getOrElse { UUID.nameUUIDFromBytes(toByteArray(Charsets.UTF_8)) }
            }

            // FREE-FORM, non-UUID ids — exactly the crash path the verifier called out:
            // pre-toStableUuid, UUID.fromString("parent-1") threw IllegalArgumentException
            // straight out of recordAgentRun.
            val parentAgentId = "parent-1"
            val childAgentId = "child-1"
            val parentTask = AgentTask(id = "task-parent-1", input = "parent work")
            val childTask =
                AgentTask(
                    id = "task-child-1",
                    input = "child work",
                    parentRunId = parentAgentId,
                )

            // CR-02 / run-NEVER-throws invariant: these calls MUST NOT throw on a non-UUID id.
            // No-exception is implicit — the test body fails if either throws.
            adapter.recordAgentRun(parentAgentId, parentTask, AgentResult.Success("parent done", TokenUsage(1, 1)))
            // CR-02 / run-NEVER-throws invariant: a non-UUID parentRunId must not crash the write.
            adapter.recordAgentRun(childAgentId, childTask, AgentResult.Success("child done", TokenUsage(2, 2)))

            val expectedChildPk = childAgentId.toStableUuid()
            val expectedParentPk = parentAgentId.toStableUuid()

            postgres.createConnection("").use { conn ->
                val countPs = conn.prepareStatement("SELECT count(*) FROM agent_runs WHERE id = ?::uuid")
                countPs.setString(1, expectedChildPk.toString())
                val countRs = countPs.executeQuery()
                countRs.next() shouldBe true
                countRs.getInt(1) shouldBe 1

                val parentPs = conn.prepareStatement("SELECT parent_run_id::text FROM agent_runs WHERE id = ?::uuid")
                parentPs.setString(1, expectedChildPk.toString())
                val parentRs = parentPs.executeQuery()
                parentRs.next() shouldBe true
                parentRs.getString("parent_run_id") shouldBe expectedParentPk.toString()
            }
        }

    @Test
    fun `recordToolCall inserts row with correct tool_name`() =
        runTest {
            val agentId = UUID.randomUUID().toString()
            val task = AgentTask(id = UUID.randomUUID().toString(), input = "tool test")
            adapter.recordAgentRun(agentId, task, AgentResult.Success("ok", TokenUsage(1, 1)))

            val toolCall = ToolCall(id = UUID.randomUUID().toString(), name = "file_read", arguments = """{"path":"/tmp/test.txt"}""")
            val toolResult = ToolResult(toolCallId = toolCall.id, content = "file contents")

            adapter.recordToolCall(agentId, toolCall, toolResult)

            postgres.createConnection("").use { conn ->
                val ps = conn.prepareStatement("SELECT tool_name FROM tool_calls WHERE run_id = ?::uuid")
                ps.setString(1, agentId)
                val rs = ps.executeQuery()
                rs.next() shouldBe true
                rs.getString("tool_name") shouldBe "file_read"
            }
        }
}
