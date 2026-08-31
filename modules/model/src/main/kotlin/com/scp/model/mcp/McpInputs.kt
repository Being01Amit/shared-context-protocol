package com.scp.model.mcp

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Boundary DTOs for the eight MCP tools. Shape is enforced by kotlinx.serialization,
 * constraints by the Konform validations in [McpValidations] — nothing unvalidated
 * crosses the MCP interface.
 */
@Serializable
public data class CreateProjectInput(
    val name: String,
    val description: String = "",
)

@Serializable
public data class NewEntry(
    val title: String,
    val content: String,
    val type: ContextType,
    val tags: List<String> = emptyList(),
    val priority: Int = ContextEntry.DEFAULT_PRIORITY,
    /**
     * Optional; defaults to the update time. Parsed leniently (see [LenientInstantSerializer])
     * because a timestamp a model formatted its own way must not discard the whole call.
     */
    @Serializable(with = LenientInstantSerializer::class)
    val timestamp: Instant? = null,
)

@Serializable
public data class NewDecision(
    val title: String,
    val decision: String,
    val reason: String = "",
)

@Serializable
public data class NewTodo(
    val description: String,
    val owner: String? = null,
)

@Serializable
public data class FileUpdate(
    val path: String,
    val summary: String = "",
    val hash: String = "",
)

@Serializable
public data class UpdateContextInput(
    val projectName: String,
    val toolName: String,
    val sessionId: String? = null,
    val summary: String = "",
    /** Where the next agent should start. Surfaced first by hydrate_context. */
    val nextStep: String = "",
    val keepOpen: Boolean = false,
    val tokenUsage: Long? = null,
    val entries: List<NewEntry> = emptyList(),
    val decisions: List<NewDecision> = emptyList(),
    val todos: List<NewTodo> = emptyList(),
    val files: List<FileUpdate> = emptyList(),
)

@Serializable
public data class HydrateContextInput(
    val projectName: String,
    val tags: List<String> = emptyList(),
    val tokenLimit: Int? = null,
)

@Serializable
public data class SearchContextInput(
    val query: String,
    val projectName: String? = null,
    val type: ContextType? = null,
    val tag: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Long? = null,
)

@Serializable
public data class ProjectSummaryInput(
    val projectName: String,
)

@Serializable
public data class TimelineInput(
    val projectName: String,
    val limit: Long? = null,
)

@Serializable
public data class SaveNoteInput(
    val projectName: String,
    val toolName: String,
    val title: String,
    val content: String,
    val type: ContextType = ContextType.LEARNING,
    val tags: List<String> = emptyList(),
    val priority: Int = ContextEntry.DEFAULT_PRIORITY,
)
