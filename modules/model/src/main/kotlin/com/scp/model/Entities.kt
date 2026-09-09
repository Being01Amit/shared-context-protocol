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
    val gitBranch: String? = null,
    val gitCommit: String? = null,
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
    val embedding: FloatArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContextEntry) return false
        if (id != other.id) return false
        if (sessionId != other.sessionId) return false
        if (timestamp != other.timestamp) return false
        if (title != other.title) return false
        if (content != other.content) return false
        if (type != other.type) return false
        if (tags != other.tags) return false
        if (priority != other.priority) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + priority
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }

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
