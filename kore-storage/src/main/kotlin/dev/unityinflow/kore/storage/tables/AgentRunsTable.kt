package dev.unityinflow.kore.storage.tables

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object AgentRunsTable : UUIDTable("agent_runs") {
    val agentName = varchar("agent_name", 255)
    val task = text("task")
    val resultType = varchar("result_type", 50)
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val metadata = jsonb("metadata") // JSONB column matching V1__init_schema.sql DDL
}
