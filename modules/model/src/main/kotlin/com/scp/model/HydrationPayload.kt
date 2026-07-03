package com.scp.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * The bounded hydration result. Section order is fixed (docs/05 §5); every list is
 * token-budgeted and truncation is always signaled via [omittedCount]/[truncationNotice],
 * never silent.
 */
@Serializable
public data class HydrationPayload(
    val projectName: String,
    val projectSummary: String,
    val recentSessions: List<SessionBrief> = emptyList(),
    val openDecisions: List<DecisionBrief> = emptyList(),
    val openTodos: List<TodoBrief> = emptyList(),
    val openBugs: List<EntryBrief> = emptyList(),
    val relevantPrompts: List<EntryBrief> = emptyList(),
    val recentFiles: List<FileBrief> = emptyList(),
    val currentPriorities: List<PriorityBrief> = emptyList(),
    val omittedCount: Int = 0,
    val truncationNotice: String? = null,
    val estimatedTokens: Int = 0,
)

@Serializable
public data class SessionBrief(
    val id: String,
    val toolName: String,
    val startTime: Instant,
    val endTime: Instant?,
    val status: SessionStatus,
    val summary: String,
)

@Serializable
public data class DecisionBrief(
    val id: String,
    val title: String,
    val decision: String,
    val reason: String,
    val updatedAt: Instant,
)

@Serializable
public data class TodoBrief(
    val id: String,
    val description: String,
    val status: TodoStatus,
    val owner: String?,
    val createdAt: Instant,
)

@Serializable
public data class EntryBrief(
    val id: String,
    val title: String,
    val content: String,
    val type: ContextType,
    val tags: List<String>,
    val priority: Int,
    val timestamp: Instant,
)

@Serializable
public data class FileBrief(
    val path: String,
    val summary: String,
    val updatedAt: Instant,
)

/** One line in the "current priorities" section: the top-scoring open items across sections 3-5. */
@Serializable
public data class PriorityBrief(
    val kind: String,
    val id: String,
    val title: String,
    val score: Double,
)
