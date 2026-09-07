package com.scp.core.usecase

import com.scp.model.NotFoundException
import com.scp.model.mcp.UpdateTodoStatusInput
import com.scp.model.mcp.UpdateTodoStatusResult
import com.scp.model.port.Clock
import com.scp.model.port.ProjectRepository
import com.scp.model.port.TodoRepository
import com.scp.model.port.TransactionRunner

/**
 * Marks a todo's lifecycle status. No state machine is enforced — any status is reachable
 * from any other, matching the schema's CHECK constraint, which is the only rule the
 * database itself imposes.
 */
public class UpdateTodoStatusUseCase(
    private val projects: ProjectRepository,
    private val todos: TodoRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    public fun execute(input: UpdateTodoStatusInput): UpdateTodoStatusResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")

        return transactions.inWriteTransaction {
            todos.listByProject(project.id).firstOrNull { it.id == input.todoId }
                ?: throw NotFoundException("Todo '${input.todoId}' not found in project '${input.projectName}'")

            todos.updateStatus(input.todoId, input.status)
            projects.touch(project.id, clock.now())

            UpdateTodoStatusResult(todoId = input.todoId, status = input.status)
        }
    }
}
