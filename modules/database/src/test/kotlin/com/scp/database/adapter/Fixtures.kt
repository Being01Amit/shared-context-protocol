package com.scp.database.adapter

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Project
import com.scp.model.Session
import kotlinx.datetime.Instant
import java.util.UUID

internal object Fixtures {
    val t0: Instant = Instant.parse("2026-07-01T10:00:00Z")

    fun project(name: String = "test-project"): Project =
        Project(id = UUID.randomUUID().toString(), name = name, description = "d", createdAt = t0, updatedAt = t0)

    fun session(projectId: String, tool: String = "claude-code"): Session =
        Session(id = UUID.randomUUID().toString(), projectId = projectId, toolName = tool, startTime = t0)

    fun entry(
        sessionId: String,
        title: String = "title",
        content: String = "content",
        type: ContextType = ContextType.FEATURE,
        tags: List<String> = emptyList(),
        priority: Int = 3,
        timestamp: Instant = t0,
    ): ContextEntry =
        ContextEntry(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestamp = timestamp,
            title = title,
            content = content,
            type = type,
            tags = tags,
            priority = priority,
        )
}
