package com.scp.core.usecase

import com.scp.model.InvalidInputException
import com.scp.model.Project
import com.scp.model.mcp.CreateProjectInput
import com.scp.model.mcp.CreateProjectResult
import com.scp.model.port.Clock
import com.scp.model.port.IdGenerator
import com.scp.model.port.ProjectRepository
import com.scp.model.port.TransactionRunner

public class CreateProjectUseCase(
    private val projects: ProjectRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    public fun execute(input: CreateProjectInput): CreateProjectResult =
        transactions.inWriteTransaction {
            if (projects.findByName(input.name) != null) {
                throw InvalidInputException("Project '${input.name}' already exists")
            }
            val now = clock.now()
            val project =
                Project(
                    id = ids.newId(),
                    name = input.name,
                    description = input.description,
                    createdAt = now,
                    updatedAt = now,
                )
            projects.insert(project)
            CreateProjectResult(projectId = project.id, name = project.name)
        }
}
