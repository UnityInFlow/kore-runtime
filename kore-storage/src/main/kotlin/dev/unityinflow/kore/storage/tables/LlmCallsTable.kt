package dev.unityinflow.kore.storage.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object LlmCallsTable : UUIDTable("llm_calls") {
    val runId = reference("run_id", AgentRunsTable, onDelete = ReferenceOption.CASCADE)
    val model = varchar("model", 255)
    val tokensIn = integer("tokens_in")
    val tokensOut = integer("tokens_out")
    val durationMs = integer("duration_ms")
    val metadata = text("metadata")
}
