package com.scp.model.mcp

import com.scp.model.ContextType
import com.scp.model.DecisionBrief
import com.scp.model.EntryBrief
import com.scp.model.SessionBrief
import com.scp.model.TodoBrief
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
public data class CreateProjectResult(
    val projectId: String,
    val name: String,
)

@Serializable
public data class UpdateContextResult(
    val sessionId: String,
    val sessionWasCreated: Boolean,
    val sessionClosed: Boolean,
    val entriesWritten: Int,
    val decisionsWritten: Int,
    val todosWritten: Int,
    val filesUpserted: Int,
    val markdownPath: String,
)

@Serializable
public data class SearchResultItem(
    val entry: EntryBrief,
    val projectName: String,
    val toolName: String,
    val score: Double,
)

@Serializable
public data class SearchContextResult(
    val items: List<SearchResultItem>,
    val totalShown: Int,
    val limitApplied: Long,
)

@Serializable
public data class TypeCount(
    val type: ContextType,
    val count: Long,
)

@Serializable
public data class ProjectSummaryResult(
    val projectName: String,
    val description: String,
    val createdAt: Instant,
    val sessionCount: Long,
    val entryCount: Long,
    val entriesByType: List<TypeCount>,
    val majorDecisions: List<DecisionBrief>,
    val openTodos: List<TodoBrief>,
    val recentSessions: List<SessionBrief>,
)

@Serializable
public data class TimelineSession(
    val session: SessionBrief,
    val entries: List<EntryBrief>,
)

@Serializable
public data class TimelineResult(
    val projectName: String,
    val sessions: List<TimelineSession>,
    val totalSessions: Long,
    val truncated: Boolean,
)

@Serializable
public data class ProjectListItem(
    val id: String,
    val name: String,
    val description: String,
    val updatedAt: Instant,
    val sessionCount: Long,
)

@Serializable
public data class ListProjectsResult(
    val projects: List<ProjectListItem>,
)

@Serializable
public data class SaveNoteResult(
    val entryId: String,
    val sessionId: String,
    val sessionWasCreated: Boolean,
)
