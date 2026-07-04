package com.scp.core.usecase

import com.scp.model.mcp.ListProjectsResult
import com.scp.model.mcp.ProjectListItem
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository

public class ListProjectsUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
) {
    public fun execute(): ListProjectsResult =
        ListProjectsResult(
            projects =
                projects.listAll().map { project ->
                    ProjectListItem(
                        id = project.id,
                        name = project.name,
                        description = project.description,
                        updatedAt = project.updatedAt,
                        sessionCount = sessions.countByProject(project.id),
                    )
                },
        )
}
