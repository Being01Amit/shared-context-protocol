package com.scp.core.usecase

import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Todo
import com.scp.model.TodoStatus
import com.scp.model.mcp.ClaimTodoInput
import com.scp.model.mcp.ClaimTodoResult
import com.scp.model.port.Clock
import com.scp.model.port.ProjectRepository
import com.scp.model.port.TodoRepository
import com.scp.model.port.TransactionRunner

/**
 * Claims a todo for the calling tool. Compare-and-swap: succeeds if the todo is currently
 * unclaimed or already claimed by the same tool (idempotent re-claim); otherwise rejects
 * with the current owner's name so the caller knows who to coordinate with. Safety comes
 * from running the read-then-write inside inWriteTransaction (BEGIN IMMEDIATE) — the same
 * atomicity guarantee SessionResolver's check-then-act relies on.
 */
public class ClaimTodoUseCase(
    private val projects: ProjectRepository,
    private val todos: TodoRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    public fun execute(input: ClaimTodoInput): ClaimTodoResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")

        return transactions.inWriteTransaction {
            val todo =
                todos.listByProject(project.id).firstOrNull { it.id == input.todoId }
                    ?: throw NotFoundException("Todo '${input.todoId}' not found in project '${input.projectName}'")
            requireClaimable(todo, input.toolName)

            todos.updateOwner(input.todoId, input.toolName)
            projects.touch(project.id, clock.now())

            ClaimTodoResult(todoId = input.todoId, owner = input.toolName)
        }
    }

    private fun requireClaimable(todo: Todo, toolName: String) {
        val problem =
            when {
                // Finished work can't be taken on; reopen it with update_todo_status first.
                todo.status == TodoStatus.DONE || todo.status == TodoStatus.DROPPED ->
                    "Todo '${todo.id}' is ${todo.status.dbValue} — set it back to open with update_todo_status before claiming it"
                todo.owner != null && todo.owner != toolName -> "Todo '${todo.id}' is already claimed by '${todo.owner}'"
                else -> null
            }
        if (problem != null) throw InvalidInputException(problem)
    }
}
