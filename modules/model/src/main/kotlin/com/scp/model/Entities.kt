package com.scp.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Domain entities. All IDs are UUID v4 strings; all timestamps are UTC [Instant]s
 * persisted as ISO 8601 text (lexicographic order == chronological order).
 *
 * The reserved `embedding` BLOB column on context entries is intentionally absent here:
 * it is unused in v1 and lives only in the schema so future vector support needs no migration.
 */
@Serializable
public data class Project(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A work period by one tool. [summary] records what happened; [nextStep] records where the
 * next agent should start — the two answer different questions and hydration surfaces both.
 */
@Serializable
public data class Session(
    val id: String,
    val projectId: String,
    val toolName: String,
    val startTime: Instant,
    val endTime: Instant? = null,
    val summary: String = "",
    val tokenUsage: Long? = null,
    val status: SessionStatus = SessionStatus.OPEN,
    val nextStep: String = "",
)

@Serializable
public data class ContextEntry(
    val id: String,
    val sessionId: String,
    val timestamp: Instant,
    val title: String,
    val content: String,
    val type: ContextType,
    val tags: List<String> = emptyList(),
    val priority: Int = DEFAULT_PRIORITY,
) {
    public companion object {
        public const val MIN_PRIORITY: Int = 1
        public const val MAX_PRIORITY: Int = 5
        public const val DEFAULT_PRIORITY: Int = 3
    }
}

@Serializable
public data class TrackedFile(
    val id: String,
    val projectId: String,
    val path: String,
    val summary: String = "",
    val hash: String = "",
    val updatedAt: Instant,
)

@Serializable
public data class Decision(
    val id: String,
    val projectId: String,
    val title: String,
    val decision: String,
    val reason: String = "",
    val status: DecisionStatus = DecisionStatus.OPEN,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
public data class Todo(
    val id: String,
    val projectId: String,
    val description: String,
    val status: TodoStatus = TodoStatus.OPEN,
    val owner: String? = null,
    val createdAt: Instant,
)
