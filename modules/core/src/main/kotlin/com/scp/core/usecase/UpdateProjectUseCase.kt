package com.scp.core.usecase

import com.scp.model.NotFoundException
import com.scp.model.mcp.UpdateProjectInput
import com.scp.model.mcp.UpdateProjectResult
import com.scp.model.port.Clock
import com.scp.model.port.ProjectRepository
import com.scp.model.port.TransactionRunner

/**
 * Edits a project's description after creation. Unlike UpdateContextUseCase, this does NOT
 * redact input.description — matching CreateProjectUseCase's existing (pre-existing, not
 * fixed here) behavior of never redacting project descriptions.
 */
public class UpdateProjectUseCase(
    private val projects: ProjectRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    public fun execute(input: UpdateProjectInput): UpdateProjectResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")

        return transactions.inWriteTransaction {
            val now = clock.now()
            projects.updateDescription(project.id, input.description, now)
            UpdateProjectResult(projectId = project.id, name = project.name, description = input.description, updatedAt = now)
        }
    }
}
