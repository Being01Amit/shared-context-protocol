package com.scp.database.adapter

import com.scp.database.Context_entry
import com.scp.database.Decision
import com.scp.database.File
import com.scp.database.Project
import com.scp.database.Session
import com.scp.database.Todo
import com.scp.model.DecisionStatus
import com.scp.model.SessionStatus
import com.scp.model.TodoStatus
import com.scp.model.TrackedFile
import kotlinx.datetime.Instant

/** Row -> domain mapping. Timestamps are ISO 8601 UTC TEXT (ADR-7). */
internal fun Project.toDomain(): com.scp.model.Project =
    com.scp.model.Project(
        id = id,
        name = name,
        description = description,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

internal fun Session.toDomain(): com.scp.model.Session =
    com.scp.model.Session(
        id = id,
        projectId = project_id,
        toolName = tool_name,
        startTime = Instant.parse(start_time),
        endTime = end_time?.let(Instant::parse),
        summary = summary,
        tokenUsage = token_usage,
        status = SessionStatus.fromDb(status),
    )

internal fun Context_entry.toDomain(tags: List<String>): com.scp.model.ContextEntry =
    com.scp.model.ContextEntry(
        id = id,
        sessionId = session_id,
        timestamp = Instant.parse(timestamp),
        title = title,
        content = content,
        type = type,
        tags = tags,
        priority = priority.toInt(),
    )

internal fun Decision.toDomain(): com.scp.model.Decision =
    com.scp.model.Decision(
        id = id,
        projectId = project_id,
        title = title,
        decision = decision,
        reason = reason,
        status = DecisionStatus.fromDb(status),
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

internal fun Todo.toDomain(): com.scp.model.Todo =
    com.scp.model.Todo(
        id = id,
        projectId = project_id,
        description = description,
        status = TodoStatus.fromDb(status),
        owner = owner,
        createdAt = Instant.parse(created_at),
    )

internal fun File.toDomain(): TrackedFile =
    TrackedFile(
        id = id,
        projectId = project_id,
        path = path,
        summary = summary,
        hash = hash,
        updatedAt = Instant.parse(updated_at),
    )
