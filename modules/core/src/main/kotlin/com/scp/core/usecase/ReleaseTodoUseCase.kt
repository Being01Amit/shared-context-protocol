package com.scp.core.usecase

import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Todo
import com.scp.model.mcp.ReleaseTodoInput
import com.scp.model.mcp.ReleaseTodoResult
import com.scp.model.port.Clock
import com.scp.model.port.ProjectRepository
import com.scp.model.port.TodoRepository
import com.scp.model.port.TransactionRunner

/**
 * Releases a todo claimed by the calling tool. Only succeeds if the caller currently owns
 * it — releasing someone else's claim, or a todo nobody has claimed, is rejected rather
 * than silently no-op'd.
 */
public class ReleaseTodoUseCase(
    private val projects: ProjectRepository,
    private val todos: TodoRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    public fun execute(input: ReleaseTodoInput): ReleaseTodoResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")

        return transactions.inWriteTransaction {
            val todo =
                todos.listByProject(project.id).firstOrNull { it.id == input.todoId }
                    ?: throw NotFoundException("Todo '${input.todoId}' not found in project '${input.projectName}'")
            requireOwnedBy(todo, input.toolName)

            todos.updateOwner(input.todoId, null)
            projects.touch(project.id, clock.now())

            ReleaseTodoResult(todoId = input.todoId)
        }
    }

    private fun requireOwnedBy(todo: Todo, toolName: String) {
        if (todo.owner != toolName) {
            throw InvalidInputException(
                "Todo '${todo.id}' is not claimed by '$toolName' (current owner: ${todo.owner ?: "none"})",
            )
        }
    }
}
