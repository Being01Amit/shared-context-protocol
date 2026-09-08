package com.scp.skills

import com.scp.core.usecase.ClaimTodoUseCase
import com.scp.core.usecase.ReleaseTodoUseCase
import com.scp.model.mcp.ClaimTodoInput
import com.scp.model.mcp.ClaimTodoResult
import com.scp.model.mcp.ReleaseTodoInput
import com.scp.model.mcp.ReleaseTodoResult

/** claim_todo — claim a todo for the calling tool, compare-and-swap. */
public class ClaimTodo(private val useCase: ClaimTodoUseCase) {
    public fun execute(input: ClaimTodoInput): ClaimTodoResult =
        logged("claim_todo", input.projectName) { useCase.execute(input) }
}

/** release_todo — release a todo claimed by the calling tool. */
public class ReleaseTodo(private val useCase: ReleaseTodoUseCase) {
    public fun execute(input: ReleaseTodoInput): ReleaseTodoResult =
        logged("release_todo", input.projectName) { useCase.execute(input) }
}
