package com.scp.skills

import com.scp.core.usecase.UpdateDecisionStatusUseCase
import com.scp.core.usecase.UpdateTodoStatusUseCase
import com.scp.model.mcp.UpdateDecisionStatusInput
import com.scp.model.mcp.UpdateDecisionStatusResult
import com.scp.model.mcp.UpdateTodoStatusInput
import com.scp.model.mcp.UpdateTodoStatusResult

/** update_todo_status — mark a todo open/in_progress/done/dropped. */
public class UpdateTodoStatus(private val useCase: UpdateTodoStatusUseCase) {
    public fun execute(input: UpdateTodoStatusInput): UpdateTodoStatusResult =
        logged("update_todo_status", input.projectName) { useCase.execute(input) }
}

/** update_decision_status — mark a decision open/accepted/superseded/rejected. */
public class UpdateDecisionStatus(private val useCase: UpdateDecisionStatusUseCase) {
    public fun execute(input: UpdateDecisionStatusInput): UpdateDecisionStatusResult =
        logged("update_decision_status", input.projectName) { useCase.execute(input) }
}
