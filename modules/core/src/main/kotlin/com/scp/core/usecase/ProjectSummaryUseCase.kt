package com.scp.core.usecase

import com.scp.core.toBrief
import com.scp.model.DecisionStatus
import com.scp.model.NotFoundException
import com.scp.model.mcp.ProjectSummaryInput
import com.scp.model.mcp.ProjectSummaryResult
import com.scp.model.mcp.TypeCount
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.DecisionRepository
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository
import com.scp.model.port.TodoRepository

public class ProjectSummaryUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
    private val entries: ContextEntryRepository,
    private val decisions: DecisionRepository,
    private val todos: TodoRepository,
) {
    public fun execute(input: ProjectSummaryInput): ProjectSummaryResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")
        val majorDecisions =
            decisions
                .listByProject(project.id)
                .filter { it.status == DecisionStatus.OPEN || it.status == DecisionStatus.ACCEPTED }
                .sortedByDescending { it.updatedAt }
                .take(MAX_DECISIONS)
        return ProjectSummaryResult(
            projectName = project.name,
            description = project.description,
            createdAt = project.createdAt,
            sessionCount = sessions.countByProject(project.id),
            entryCount = entries.countByProject(project.id),
            entriesByType = entries.countByType(project.id).map { (type, count) -> TypeCount(type, count) },
            majorDecisions = majorDecisions.map { it.toBrief() },
            openTodos = todos.findOpenByProject(project.id).take(MAX_TODOS).map { it.toBrief() },
            recentSessions = sessions.listRecent(project.id, RECENT_SESSIONS).map { it.toBrief() },
        )
    }

    private companion object {
        const val MAX_DECISIONS = 20
        const val MAX_TODOS = 50
        const val RECENT_SESSIONS = 5L
    }
}
